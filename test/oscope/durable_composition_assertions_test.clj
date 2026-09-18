(ns oscope.durable-composition-assertions-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.native :as native]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.durable-composition-assertions :as correspondence]
            [oscope.durable-s3-integration-test :as wire-fixture]
            [oscope.http-executor :as http-executor]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.schema :as schema]
            [otel.otlp.signal-decode :as signal-decode]
            [otel.otlp.trace-decode :as trace-decode]))

(def contract
  {:startup-owners [:exporter :server]
   :logical-batches [:traces :logs :metrics]
   :checkpoint-every 2})

(defn- checked-inputs! [now]
  ;; Establish complete nonempty input BEFORE construction or instrumentation.
  ;; Actual SDK decoders, not a custom decoder or HTTP200/partialSuccess oracle.
  (mapv
   (fn [[signal path wire-name]]
     (let [payload ((ns-resolve 'oscope.durable-s3-integration-test wire-name) now)
           decoded (case signal
                     :traces (trace-decode/decode-request payload)
                     :logs (signal-decode/decode-logs payload)
                     :metrics (signal-decode/decode-metrics payload))
           complete?
           (and (empty? (:errors decoded))
                (case signal
                  :traces (and (= 0 (:rejected-spans decoded))
                               (= 1 (count (:spans decoded))))
                  :logs (and (= 0 (:rejected-log-records decoded))
                             (= 1 (count (:records decoded))))
                  :metrics
                  (let [metrics (vec (mapcat
                                      (fn [{:keys [collected]}]
                                        (mapcat :metrics collected))
                                      (:collections decoded)))]
                    (and (= 0 (:rejected-data-points decoded))
                         (= 3 (count metrics))
                         (= {:gauge 1 :sum 1 :histogram 1}
                            (frequencies (map :type metrics)))
                         (every? #(= 1 (count (:data-points %))) metrics)))))]
       (when-not complete?
         (throw (ex-info "composition fixture input coverage failed"
                         {:obligation :input-coverage :signal signal})))
       [signal path payload]))
   [[:traces "/v1/traces" 'trace-wire]
    [:logs "/v1/logs" 'log-wire]
    [:metrics "/v1/metrics" 'metric-wire]]))

(defn- table-category [sql]
  ;; Inspect only the fixed statement prefix; never retain statement/data text.
  (some (fn [[prefix category]] (when (str/starts-with? sql prefix) category))
        [["insert into otel_traces" :traces]
         ["insert into otel_logs" :logs]
         ["insert into otel_metrics_gauge" :gauge]
         ["insert into otel_metrics_sum" :sum]
         ["insert into otel_metrics_histogram" :histogram]]))

(defn- run-composition [mutation]
  ;; Actual exporter/server/OTLP implementations; only native/JDBC and unrelated
  ;; listener/executor/viewer resources are fake. Not native or socket evidence.
  (let [inputs (checked-inputs! 3000000)
        events (atom [])
        batch (atom :startup)
        pending? (atom false)
        handler (atom nil)
        callback (atom nil)
        worker (atom nil)
        worker-error (atom nil)
        blocked (promise)
        release (promise)
        premature? (atom false)
        emit! #(swap! events conj %)
        publish! (fn [kind owner]
                   (emit! {:event :publication :batch @batch
                           :owner owner :kind kind})
                   (reset! pending? false)
                   {:status :committed})
        barrier! (fn [kind owner]
                   (emit! {:event :barrier-start :batch @batch
                           :owner owner :kind kind})
                   (when (and (= mutation :premature-ack)
                              (= owner :server) (= @batch :traces))
                     (deliver blocked true)
                     (when-not (= true (deref release 1000 ::timeout))
                       (throw (ex-info "pure barrier release timed out"
                                       {:obligation :fixture-settlement}))))
                   (let [result (if (= kind :checkpoint)
                                  (publish! :checkpoint owner)
                                  (if @pending? (publish! :wal owner)
                                      {:status :empty}))]
                     (emit! {:event :barrier-end :batch @batch
                             :owner owner :kind kind})
                     result))
        actual-exporter chdb-export/exporter
        actual-otlp otlp/handler
        connection (reify java.io.Closeable (close [_] nil))]
    (with-redefs [native/ensure-loaded! (fn [] nil)
                  native/chdb-version (constantly "26.7.3")
                  durable/connection-role (constantly :writer)
                  durable/checkpoint! (fn [_] (barrier! :checkpoint :exporter))
                  durable/flush! (fn [_] (barrier! :flush :exporter))
                  schema/ensure-schema! (fn [_] (emit! {:event :schema :batch :startup}))
                  jdbc/connection (constantly connection)
                  jdbc/execute! (fn [_ sql & _]
                                  (let [table (table-category sql)]
                                    (when-not table
                                      (throw (ex-info "unexpected pure insert category"
                                                      {:obligation :fixture-input})))
                                    (emit! {:event :insert :batch @batch :table table})
                                    (reset! pending? true)
                                    ;; Execute the real server completion callback
                                    ;; per physical insert: do not edit its transcript.
                                    (when (and (= mutation :per-insert-completion)
                                               (= @batch :metrics))
                                      (@callback))
                                    {:count 1}))
                  chdb-export/exporter
                  (fn [options]
                    (emit! {:event :exporter-start :batch :startup})
                    ;; The actual factory's public branch omits its startup
                    ;; checkpoint; no replacement exporter implementation.
                    (actual-exporter (cond-> options
                                       (= mutation :missing-startup-barrier)
                                       (assoc :create-schema? false))))
                  otlp/handler
                  (fn [exporter options]
                    (let [complete! (:after-success! options)]
                      (reset! callback complete!)
                      (actual-otlp
                       exporter
                       (if (= mutation :premature-ack)
                         (assoc options :after-success!
                                (fn []
                                  (if (= @batch :traces)
                                    (let [thread (Thread.
                                                  #(try (complete!)
                                                        (catch Throwable error
                                                          (reset! worker-error error))))]
                                      (reset! worker thread)
                                      (.start thread)
                                      true)
                                    (complete!))))
                         options))))
                  http-executor/start! (constantly {:executor nil})
                  http-executor/stop! (constantly true)
                  live/open! (constantly {:close! (fn [])})
                  live/close! (fn [_] nil)
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [app & _]
                                    (reset! handler app)
                                    (emit! {:event :ingress :batch :startup})
                                    {:port 19999})
                  http/stop-server (fn [_] nil)]
      (let [lifecycle
            (server/start!
             {:port 0 :db-spec {:vendor "chdb-durable"}
              :durability {:checkpoint! (fn [_] (barrier! :checkpoint :server))
                           :flush! (fn [_] (barrier! :flush :server))
                           :checkpoint-every-batches
                           (if (= mutation :wrong-cadence) 3 2)}})]
        (try
          (doseq [[signal path payload] inputs]
            (reset! batch signal)
            ;; This app-side observation is deliberately categorical. It binds
            ;; the real OTLP handler invocation to the later Durable boundary
            ;; without retaining payload, telemetry, or endpoint values.
            (emit! {:event :application :batch signal :phase :observed})
            (emit! {:event :request :batch signal})
            (let [body (json/write-str payload)
                  response (@handler {:request-method :post :uri path
                                      :headers {"host" "127.0.0.1:19999"
                                                "content-type" "application/json"
                                                "content-length" (str (count body))}
                                      :body body})]
              (emit! {:event :response :batch signal
                      :outcome (if (= 200 (:status response)) :success :failure)})
              (when (and (= mutation :premature-ack) (= signal :traces))
                ;; A real callback is blocked while the actual handler returns200.
                (reset! premature? (and (= 200 (:status response))
                                         (= true (deref blocked 1000 ::timeout))))
                (deliver release true)
                (.join @worker 1000)
                (when (or (.isAlive @worker) @worker-error)
                  (throw (ex-info "pure callback did not settle"
                                  {:obligation :fixture-settlement}))))))
          {:premature? @premature?}
          (finally
            (deliver release true)
            (when-let [thread @worker]
              (.join thread 1000)
              (when (.isAlive thread)
                (throw (ex-info "pure worker remains live"
                                {:obligation :fixture-settlement}))))
            (server/stop! lifecycle)))
        ;; Pure composition has no native-reader authority. This receipt only
        ;; proves the categorical handoff shape and is intentionally rejected
        ;; if it is not the fresh settled reader expected by this fixture.
        (emit! {:event :generation-receipt :batch :shutdown
                :reader (if (= mutation :wrong-generation-receipt) :wrong :fresh)
                :settlement :settled})
        {:events @events :premature? @premature?}))))

(defn- rejected-obligation [events]
  (try (correspondence/assert-correspondence! events contract)
       nil (catch Throwable error (:obligation (ex-data error)))))

(defn- receipt-before-final-response [events]
  ;; Keep the categorical receipt itself valid and move only its causal
  ;; position. This is intentionally a witness mutant, not a second exporter,
  ;; server, or native implementation.
  (let [receipt (peek events)
        prefix (pop events)
        response-index (last (keep-indexed (fn [index observation]
                                             (when (= :response (:event observation))
                                               index))
                                           prefix))]
    (vec (concat (subvec prefix 0 response-index)
                 [receipt]
                 (subvec prefix response-index)))))

(deftest decoded-input-coverage-precedes-composition
  (is (= 3 (count (checked-inputs! 3000000))))
  (is (= :input-coverage
         (try (checked-inputs! 1) nil
              (catch Throwable error (:obligation (ex-data error)))))
      "old invalid timestamp cannot enter composition via partial200"))

(deftest real-consumer-composition-projects-startup-and-logical-batches
  (let [{:keys [events]} (run-composition nil)
        derived (correspondence/publication-projection contract)]
    (is (= derived (correspondence/assert-correspondence! events contract)))
    (is (= [:checkpoint :checkpoint :wal :wal :checkpoint :wal] derived))
    (is (not= [:checkpoint :wal :checkpoint :wal] derived)
        "353b's stale S3 oracle omits observed startup/logical composition")
    (is (= 3 (count (filter #(and (= :insert (:event %))
                                 (= :metrics (:batch %))) events))))
    (is (= 1 (count (filter #(and (= :barrier-end (:event %))
                                 (= :exporter (:owner %))
                                 (= :metrics (:batch %))) events))))
    (is (every? keyword? (mapcat vals events))
        "only closed categorical values, never statements/attributes/config")))

(deftest actual-factory-missing-startup-barrier-is-observable
  (is (= :startup-barriers
         (rejected-obligation (:events (run-composition :missing-startup-barrier))))))

(deftest unknown-and-unfinished-observations-fail-closed
  (let [events (:events (run-composition nil))]
    ;; Envelope controls are deliberately separate from executed causal mutants.
    (is (= :event-envelope (rejected-obligation (conj events {:event :unknown}))))
    (is (= :event-envelope
           (rejected-obligation (conj events {:event :request :batch :traces
                                              :attribute "not-retainable"}))))
    (is (= :boundary-completeness
           (rejected-obligation (conj events {:event :barrier-start :batch :metrics
                                              :owner :server :kind :flush}))))))

(deftest actual-server-wrong-cadence-is-observable
  (is (= :server-cadence
         (rejected-obligation (:events (run-composition :wrong-cadence))))))

(deftest physical-metric-inserts-cannot-become-logical-completions
  (is (= :logical-batch-boundaries
         (rejected-obligation (:events (run-composition :per-insert-completion))))))

(deftest actual-handler-premature-response-is-observable
  (let [{:keys [events premature?]} (run-composition :premature-ack)]
    (is (true? premature?) "actual200 returned while real completion was blocked")
    (is (= :ack-before-boundary (rejected-obligation events)))))

(deftest settled-generation-receipt-must-name-the-fresh-reader
  ;; This is the one causal joined-witness mutant. It preserves the actual
  ;; exporter/server/OTLP transcript and changes only the final categorical
  ;; receipt, so a passing correspondence check cannot hide a bad handoff.
  (is (= :generation-receipt
         (rejected-obligation
          (:events (run-composition :wrong-generation-receipt))))))

(deftest settled-generation-receipt-cannot-precede-the-final-response
  ;; Fresh/settled fields alone are insufficient: the categorical receipt must
  ;; follow publication and all successful application responses.
  (is (= :generation-receipt
         (rejected-obligation
          (receipt-before-final-response (:events (run-composition nil)))))))
