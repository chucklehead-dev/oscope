(ns oscope.readiness-listener-test
  (:require [clojure.test :refer [deftest is]]
            [db.jdbc]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.embedded :as embedded]
            [oscope.embedded.viewer :as viewer]
            [oscope.http-executor :as http-executor]
            [oscope.live :as live]
            [oscope.readiness :as readiness]
            [oscope.readiness-test :as helper]
            [oscope.server :as server]
            [oscope.ui.events :as events]
            [oscope.ui.visualization-editor :as editor]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]
            [otel.exporter.chdb :as chdb]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- native-health! [port]
  ;; Reuse the dependency's qualified bounded network APIs, not a second HTTP
  ;; provider. All connections are numeric loopback and explicitly owned.
  (require 'teensyp.client)
  (let [connect (resolve 'teensyp.client/connect)
        send-all! (resolve 'teensyp.client/send-all!)
        receive! (resolve 'teensyp.client/receive-into!)
        close! (resolve 'teensyp.client/close!)
        connection (connect "127.0.0.1" port {:connect-timeout-ms 2000})
        buffer (byte-array 4096)]
    (try
      (send-all! connection
                 (.getBytes (str "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:" port
                                 "\r\nConnection: close\r\n\r\n") "UTF-8")
                 {:timeout-ms 2000})
      (loop [response "" reads 0]
        (when (>= reads 4)
          (throw (ex-info "readiness health response exceeded bound" {})))
        (if-let [n (receive! connection buffer 0 (alength buffer) {:timeout-ms 2000})]
          (let [response (str response (String. buffer 0 n "UTF-8"))]
            (if (re-find #"HTTP/1\.1 200(?: |\r)" response)
              200
              (recur response (inc reads))))
          nil))
      (finally (close! connection)))))

(defn native-listener-gate!
  "Explicit fresh-process native port-zero gate; no mocks, remote export or
  telemetry content. root is retained parent-owned scratch."
  [kind root]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    (let [owner* (atom nil) listener* (atom nil)
          original-error* (atom nil) cleanup-errors (atom [])
          snapshot (atom nil) channel (async/chan 8)
          records (atom [])
          backend (local-posix/local-backend (str (io/file root "durable")))
          db-spec (durable/writer-dbspec
                   {:backend backend :owner "readiness-native"
                    :instance (str "readiness-" (name kind))
                    :database "default" :lease-ttl-ms 30000})
          publish! (fn [record]
                     (reset! snapshot record)
                     (swap! records conj record)
                     (async/put! channel record)
                     (when (= :ready (:status record))
                       (is (pos? (:port record)))
                       (is (= 200 (native-health! (:port record))))))
          options {:port 0 :readiness
                   {:instance-id (str "native_" (name kind))
                    :publish! publish!
                    :file (str (io/file root "readiness" "listener.edn"))}}]
      (try
        (let [listener
              (case kind
                :viewer
                (let [owner (embedded/start!
                             {:db-spec db-spec
                              :sdk-options {:service-name "readiness-native"
                                            :processor :simple :metrics? false
                                            :runtime-metrics? false :logs? false}})]
                  (reset! owner* owner)
                  (viewer/start! owner options))
                :server
                (server/start! (assoc options :db-spec db-spec
                                     :durability {:checkpoint! durable/checkpoint!
                                                  :flush! durable/flush!})))]
          (reset! listener* listener)
          (is (= :ready (:status @snapshot)))
          (is (= (:port listener) (:port @snapshot)))
          (is (= :durable (:storage-mode @snapshot)))
          (is (= @snapshot (read-string (slurp (get-in options [:readiness :file])))))
          (is (= [:starting :ready] (mapv :status @records)))
          (is (= (first @records) (async/poll! channel)))
          (is (= (second @records) (async/poll! channel)))
          (is (= {:status :closed :phase :closed} ((:stop! listener))))
          (is (= :terminal (:status @snapshot)))
          (is (= :closed (:reason @snapshot)))
          (is (= @snapshot (read-string (slurp (get-in options [:readiness :file])))))
          (is (= [:starting :ready :terminal :terminal] (mapv :status @records)))
          (is (= (nth @records 2) (async/poll! channel)))
          (is (= (nth @records 3) (async/poll! channel)))
          (is (nil? (async/poll! channel)))
          (let [before @records]
            (is (= {:status :closed :phase :closed} ((:stop! listener))))
            (is (= before @records))))
        (catch Throwable original
          (reset! original-error* original)
          ;; A failed start may have published a cleanup owner instead of a
          ;; listener handle. Attempt that opaque capability once, bounded by
          ;; its existing owned closer contracts and the outer process limit.
          (when-let [retry-stop! (:retry-stop! (ex-data original))]
            (when (fn? retry-stop!)
              (try
                (when-not (= :closed (:status (retry-stop!)))
                  (swap! cleanup-errors conj :startup-retry-incomplete))
                (catch Throwable _ (swap! cleanup-errors conj :startup-retry-threw)))))
          nil)
        (finally
          ;; Independent attempts: failure of one owner must not skip the
          ;; other owner/channel. Never log native exceptions or dbspecs.
          (when-let [listener @listener*]
            (try
              (let [first-result ((:stop! listener))
                    result (if (= :closed (:status first-result))
                             first-result ((:stop! listener)))]
                (when-not (= :closed (:status result))
                  (swap! cleanup-errors conj :listener-incomplete)))
              (catch Throwable _ (swap! cleanup-errors conj :listener-threw))))
          (when-let [owner @owner*]
            (try
              (let [result (embedded/stop! owner)]
                (is (= {:status :closed :phase :closed} result))
                (when-not (= :closed (:status result))
                  (swap! cleanup-errors conj :owner-incomplete)))
              (catch Throwable _ (swap! cleanup-errors conj :owner-threw))))
          (try (async/close! channel)
               (catch Throwable _ (swap! cleanup-errors conj :channel-threw)))))
      (when (seq @cleanup-errors)
        (println {:native-cleanup-failures @cleanup-errors}))
      ;; Preserve the first startup/gate exception; cleanup categories are
      ;; bounded separately, never attached as raw causes or config payloads.
      (when-let [original @original-error*] (throw original))
      (when (seq @cleanup-errors)
        (throw (ex-info "native readiness cleanup incomplete"
                        {:operations @cleanup-errors}))))
    @clojure.test/*report-counters*))

(defn- exercise [kind f]
  (let [seen (atom []) app (atom nil) stop-error (atom nil)
        worker-error (atom nil) listener-closed? (atom false)
        connection (reify java.io.Closeable
                     (close [_] (swap! seen conj :connection-close)))
        source ::source
        exporter (reify
                   export/SpanExporter
                   (export-spans! [_ _] true)
                   (flush-exporter! [_] true)
                   (shutdown-exporter! [_] (swap! seen conj :spans-close) true)
                   export/MetricExporter
                   (export-metrics! [_ _ _] true)
                   (shutdown-metric-exporter! [_] (swap! seen conj :metrics-close) true)
                   logs/LogRecordExporter
                   (export-logs! [_ _] true)
                   (shutdown-log-exporter! [_] (swap! seen conj :logs-close) true))]
    (with-redefs [jdbc/connection (fn [_] (swap! seen conj :connection-open) connection)
                  chdb/exporter (constantly exporter)
                  live/open! (constantly source)
                  live/close! (fn [_] (swap! seen conj :source-close))
                  http-executor/start! (fn [_]
                                         (swap! seen conj :workers-start)
                                         {:executor ::executor})
                  http-executor/stop! (fn [_]
                                        (swap! seen conj :workers-stop)
                                        (when-let [error @worker-error] (throw error)))
                  workbench/handler (fn [_] (constantly {:status 200}))
                  events/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  editor/handler (fn [_] (constantly {:status 200}))
                  http/run-server (fn [handler & _]
                                    (reset! app handler)
                                    (swap! seen conj :listener-start)
                                    {:port 12345})
                  http/stop-server (fn [_]
                                     (swap! seen conj :listener-stop)
                                     (when-let [error @stop-error] (throw error))
                                     (reset! listener-closed? true))]
      (f {:seen seen :app app :stop-error stop-error
          :worker-error worker-error :listener-closed? listener-closed?
          :start (fn [options]
                   (case kind
                     :viewer (viewer/start! {:source source :connection connection} options)
                     :server (server/start! options)))}))))

(defn- health [app authority]
  (@app {:request-method :get :uri "/healthz" :headers {"Host" authority}}))

(deftest both-listeners-ready-follows-bound-port-and-installed-authority
  (doseq [kind [:viewer :server]]
    (exercise kind
              (fn [{:keys [start app seen]}]
                (let [records (atom [])
                      lifecycle
                      (start {:port 0 :readiness
                              {:publish! (fn [record]
                                           (swap! records conj record)
                                           (when (= :ready (:status record))
                                             (is (= :listener-start (last @seen)))
                                             (is (= 12345 (:port record)))
                                             (is (= 200 (:status (health app "127.0.0.1:12345"))))
                                             (is (= 421 (:status (health app "127.0.0.1:0"))))))}})]
                  (is (= [:starting :ready] (mapv :status @records)))
                  (is (= (if (= kind :viewer) :durable :local)
                         (:storage-mode (last @records))))
                  (is (= {:status :closed :phase :closed} ((:stop! lifecycle))))
                  (is (= [:starting :ready :terminal :terminal]
                         (mapv :status @records)))
                  ((:stop! lifecycle))
                  (is (= 4 (count @records))))))))

(deftest both-listeners-ready-callback-failure-revokes-authority-and-cleans-up
  (doseq [kind [:viewer :server]]
    (exercise kind
              (fn [{:keys [start app seen]}]
                (let [records (atom [])
                      error (try
                              (start {:port 0 :readiness
                                      {:publish! (fn [record]
                                                   (swap! records conj record)
                                                   (when (= :ready (:status record))
                                                     (throw (ex-info "private credential" {}))))}})
                              nil (catch Throwable error error))]
                  (is (= :publication-failed (:reason (ex-data error))))
                  (is (nil? (.getCause error)))
                  (is (= 421 (:status (health app "127.0.0.1:12345"))))
                  (is (= :startup-failed (:reason (last @records))))
                  (is (= 1 (count (filter #{:listener-stop} @seen))))
                  (is (= 1 (count (filter #{:workers-stop} @seen))))
                  (is (= (if (= kind :server) 1 0)
                         (count (filter #{:connection-close} @seen)))))))))

(deftest both-listeners-close-failure-retains-claim-and-contender-acquires-nothing
  (doseq [kind [:viewer :server]]
    (let [{:keys [operations owner current]} (helper/fake-file)]
      (with-redefs [readiness/file-operations (constantly operations)]
        (exercise kind
                  (fn [{:keys [start seen stop-error]}]
                    (let [options {:port 0 :readiness
                                   {:file "/tmp/readiness-listener/A.edn" :instance-id "A"}}
                          lifecycle (start options)]
                      (reset! stop-error (ex-info "private cleanup" {}))
                      (is (= :closing (:status ((:stop! lifecycle)))))
                      (is (= :terminal (:status @current)))
                      (is (= :stopping (:reason @current)))
                      (is (some? @owner))
                      (let [before @seen
                            error (try (start (assoc-in options [:readiness :instance-id] "B"))
                                       nil (catch Throwable error error))]
                        (is (= :file-claim-failed (:reason (ex-data error))))
                        (is (= before @seen)))
                      (reset! stop-error nil)
                      (is (= {:status :closed :phase :closed} ((:stop! lifecycle))))
                      (is (nil? @owner))
                      (let [b (start (assoc-in options [:readiness :instance-id] "B"))
                            before @current]
                        ((:stop! lifecycle))
                        (is (= before @current))
                        ((:stop! b))))))))))

(deftest standalone-connection-failure-preserves-original-error-and-retires-discovery
  (let [original (ex-info "application startup" {}) records (atom [])]
    (with-redefs [jdbc/connection (fn [_] (throw original))]
      (let [caught (try (server/start! {:readiness {:publish! #(swap! records conj %)}})
                        nil (catch Throwable error error))]
        (is (identical? original caught))
        (is (= [:starting :terminal :terminal] (mapv :status @records)))
        (is (= [:stopping :startup-failed] (mapv :reason (rest @records))))
        (is (= :startup-failed (:reason (last @records))))))))

(deftest startup-cleanup-failure-publishes-retry-owner-and-retains-generation
  (doseq [kind [:viewer :server]]
    (let [{:keys [operations owner current]} (helper/fake-file)]
      (with-redefs [readiness/file-operations (constantly operations)]
        (exercise kind
                  (fn [{:keys [start stop-error seen app listener-closed?]}]
                    (reset! stop-error (ex-info "private stop failure" {}))
                    (let [caught (try
                                   (start {:port 0 :readiness
                                           {:file "/tmp/readiness-listener/A.edn" :instance-id "A"
                                            :publish! (fn [record]
                                                        (when (= :ready (:status record))
                                                          (throw (ex-info "private startup" {}))))}})
                                   nil (catch Throwable error error))
                          retry-stop! (:retry-stop! (ex-data caught))]
                      (is (= :oscope.readiness/startup-cleanup-incomplete (:type (ex-data caught))))
                      (is (fn? retry-stop!))
                      (is (nil? (.getCause caught)))
                      (is (some? @owner))
                      (is (= :terminal (:status @current)))
                      (is (false? @listener-closed?))
                      (is (= 421 (:status (health app "127.0.0.1:12345"))))
                      (when retry-stop!
                        (let [before @seen record @current
                              contender (try (start {:readiness
                                                     {:file "/tmp/readiness-listener/A.edn"
                                                      :instance-id "B"}})
                                             nil (catch Throwable error error))]
                          (is (= :file-claim-failed (:reason (ex-data contender))))
                          (is (= before @seen))
                          (is (= record @current)))
                        (reset! stop-error nil)
                        (is (= {:status :closed :phase :closed} (retry-stop!)))
                        (is (true? @listener-closed?))
                        (is (nil? @owner))
                        (let [before @seen]
                          (is (= {:status :closed :phase :closed} (retry-stop!)))
                          (is (= before @seen)))
                        (let [b (start {:readiness {:file "/tmp/readiness-listener/A.edn"
                                                   :instance-id "B"}})
                              record @current before @seen]
                          (is (= "B" (:instance-id record)))
                          (is (= :ready (:status record)))
                          (retry-stop!)
                          (is (= record @current))
                          (is (= before @seen))
                          ((:stop! b)))))))))))

(deftest startup-worker-failure-retries-only-after-confirmed-listener-retirement
  (doseq [kind [:viewer :server]]
    (let [{:keys [operations owner]} (helper/fake-file)]
      (with-redefs [readiness/file-operations (constantly operations)]
        (exercise kind
                  (fn [{:keys [start worker-error listener-closed? seen]}]
                    (reset! worker-error (ex-info "private worker failure" {}))
                    (let [error (try (start {:readiness
                                             {:file "/tmp/readiness-listener/A.edn" :instance-id "A"
                                              :publish! #(when (= :ready (:status %))
                                                           (throw (ex-info "private startup" {})))}})
                                     nil (catch Throwable error error))
                          retry-stop! (:retry-stop! (ex-data error))]
                      (is (true? @listener-closed?))
                      (is (some? @owner))
                      (is (= :startup-cleanup-incomplete (keyword (name (:type (ex-data error))))))
                      (is (fn? retry-stop!))
                      (when retry-stop!
                        (reset! worker-error nil)
                        (is (= {:status :closed :phase :closed} (retry-stop!)))
                        (is (= 1 (count (filter #{:listener-stop} @seen))))
                        (is (= 2 (count (filter #{:workers-stop} @seen))))
                        (is (nil? @owner))))))))))

(deftest terminal-disk-failure-is-honestly-incomplete-and-retryable
  (doseq [kind [:viewer :server]]
    (let [{:keys [operations owner current]} (helper/fake-file)
          broken? (atom false)
          atomic (:atomic-replace! operations)
          operations (assoc operations :atomic-replace!
                            (fn [temp target claim]
                              (when (and @broken? (= :terminal (:status @temp)))
                                (throw (ex-info "private disk path" {})))
                              (atomic temp target claim)))]
      (with-redefs [readiness/file-operations (constantly operations)]
        (exercise kind
                  (fn [{:keys [start seen]}]
                    (let [lifecycle (start {:readiness {:file "/tmp/readiness-listener/A.edn"
                                                      :instance-id "A"}})]
                      (reset! broken? true)
                      (is (= {:status :closing :phase :publishing-readiness
                              :errors [{:type :oscope.readiness/terminal-publication-failed}]}
                             ((:stop! lifecycle))))
                      (is (= :ready (:status @current)))
                      (is (some? @owner))
                      (let [before @seen]
                        (reset! broken? false)
                        (is (= {:status :closed :phase :closed} ((:stop! lifecycle))))
                        (is (= before @seen)))
                      (is (= :terminal (:status @current)))
                      (is (nil? @owner)))))))))
