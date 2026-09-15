(ns minimal-embedded-app
  (:require [db.jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]
            [jdbc.proto :as jdbc-proto]
            [jolt.http-client :as http]
            [jolt.socket]
            [oscope.embedded :as embedded]
            [oscope.embedded.query :as embedded-query]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def ^:private application-row
  {:id 7 :state "authoritative"})

(def ^:private span-name "minimal embedded fixture")
(def ^:private typed-attribute-key "fixture.confirmed")
(def ^:private query-timeout-ms 5000)
(def ^:private query-ready-wait-ms 15000)
(def ^:private request-wait-ms 3000)
(def ^:private cancellation-wait-ms 2000)
(def ^:private status-counter-keys
  #{:queue-size :attempted-span-count :exported-span-count
    :failed-span-count :dropped-count})

(defn- require! [condition message & [data]]
  (when-not condition
    (throw (ex-info message (or data {})))))

(defn- throws? [f]
  (try
    (f)
    false
    (catch Throwable _ true)))

(defn- closed? [connection]
  (.isClosed (jdbc-proto/connection connection)))

(defn- close-quietly! [value]
  (when value
    (try (.close value) (catch Throwable _ nil))))

(defn- read-request! [socket]
  (let [input (.getInputStream socket)
        head
        (loop [chars [] tail ""]
          (let [octet (.read input)]
            (when (neg? octet)
              (throw (ex-info "stalled peer closed before request headers" {})))
            (let [ch (char octet)
                  chars (conj chars ch)
                  tail (str tail ch)]
              (if (.endsWith tail "\r\n\r\n")
                (apply str chars)
                (recur chars (if (> (count tail) 4) (subs tail 1) tail))))))
        [_ length]
        (re-find #"(?i)\r\ncontent-length:\s*([0-9]+)\r\n"
                 (str "\r\n" head))
        length (if length (Long/parseLong length) 0)]
    (loop [remaining length]
      (when (pos? remaining)
        (when (neg? (.read input))
          (throw (ex-info "stalled peer closed before request body" {})))
        (recur (dec remaining))))
    {:head head :content-length length}))

(defn- start-stalled-peer [path]
  (let [listener (java.net.ServerSocket. 0)
        peer (atom nil)
        requests (atom 0)
        received (promise)
        acceptor
        (future
          (try
            (let [socket (.accept listener)]
              (reset! peer socket)
              (swap! requests inc)
              (deliver received (read-request! socket)))
            (catch Throwable error
              (deliver received error))))]
    {:listener listener
     :peer peer
     :requests requests
     :received received
     :acceptor acceptor
     :url (str "http://127.0.0.1:" (.getLocalPort listener) path)}))

(defn- stop-stalled-peer! [{:keys [listener peer acceptor]}]
  (close-quietly! @peer)
  (close-quietly! listener)
  (deref acceptor 2000 nil))

(defn- safe-status? [snapshot]
  (and (= #{:oscope.embedded.status/version :phase :span-pipelines :durable}
          (set (keys snapshot)))
       (= 1 (:oscope.embedded.status/version snapshot))
       (= :independent (get-in snapshot [:span-pipelines :mode]))
       (every?
        (fn [destination]
          (let [pipeline (get-in snapshot [:span-pipelines destination])]
            (and (= :available (:availability pipeline))
                 (= status-counter-keys
                    (set (remove #{:availability} (keys pipeline))))
                 (every? (fn [key]
                           (let [value (get pipeline key)]
                             (and (integer? value)
                                  (<= 0 value 9223372036854775807))))
                         status-counter-keys))))
        [:local :remote])
       (= {:view-current? :unavailable
           :last-successful-persistence :unavailable}
          (:durable snapshot))))

(defn- approved-manifest []
  (manifest/compile-manifest
   {:dataset-id "oscope-minimal-embedded"
    :application-id "fixture"
    :lineage "fixture-v1"
    :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :runtime-reviewed
      :source "test/fixtures/minimal-embedded-app"
      :entries
      [{:signal :spans
        :table "otel_traces"
        :location :span-attributes
        :key typed-attribute-key
        :type :boolean}]}]}))

(defn- await-ready [query]
  ;; Leave a separate scheduling envelope beyond the query's own timeout.
  (let [deadline (+ (System/currentTimeMillis) query-ready-wait-ms)]
    (loop []
      (let [snapshot (embedded-query/snapshot query)]
        (cond
          (= :ready (:status snapshot)) snapshot
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 5) (recur))
          :else snapshot)))))

(defn- typed-span-rows [runtime binding]
  (get-in
   ((:load-command (:source runtime))
    :minimal-embedded-readback
    {:mode :typed-span-filter
     :schema-binding binding
     :operator :eq
     :value true
     :window :15m
     :limit 10})
   [:table :rows]))

(defn -main [& [root-path]]
  (require! (and (string? root-path) (not= "" root-path))
            "fixture requires a parent-owned temporary root")
  (let [directory (.toPath (java.io.File. root-path))
        application-db (str "sqlite:" (.resolve directory "application.sqlite"))
        application* (atom nil)
        runtime* (atom nil)
        query* (atom nil)
        application-worker* (atom nil)
        application-peer (start-stalled-peer "/application-secret")
        remote-peer (start-stalled-peer "/remote-secret/v1/traces")]
    (try
      (let [application (jdbc/connection application-db)]
        (reset! application* application)
        (jdbc/execute! application
                       "create table application_state (id integer primary key, state text not null)")
        (jdbc/execute! application
                       ["insert into application_state (id, state) values (?, ?)"
                        (:id application-row) (:state application-row)])
        (require! (= [application-row]
                     (jdbc/fetch application
                                 "select id, state from application_state"))
                  "SQLite did not retain authoritative application state")

        (let [telemetry-store
              (local-posix/local-backend
               (str (.resolve directory "telemetry-store")))
              registry-store
              (local-posix/local-backend
               (str (.resolve directory "schema-registry")))
              db-spec
              (durable/writer-dbspec
               {:backend telemetry-store
                :owner "oscope-minimal-embedded-fixture"
                :instance (str "oscope-minimal-embedded-fixture-"
                               (random-uuid))
                :database "default"
                :scratch-parent (str directory)
                :lease-ttl-ms 30000})
              runtime
              (embedded/start!
               {:db-spec db-spec
                :sdk-options {:service-name "oscope-minimal-embedded-fixture"
                              :metrics? false
                              :runtime-metrics? false
                              :logs? false}
                :span-pipelines
                {:local {:schedule-delay-ms 1
                         :max-export-batch-size 1
                         :max-queue-size 4}
                 :remote {:traces-url (:url remote-peer)
                          :timeout-ms 10000
                          :max-retries 0
                          :schedule-delay-ms 1
                          :max-export-batch-size 1
                          :max-queue-size 4}}
                :typed-schema
                {:mode :install
                 :approved-manifest (approved-manifest)
                 :registry-backend registry-store}})]
          (reset! runtime* runtime)
          (let [provider (sdk/tracer-provider)
                descriptor-set (:typed-span-descriptors runtime)
                binding (first (get-in runtime [:source :typed-span-fields]))]
            (require! provider
                      "fixture did not start exactly one process SDK owner")
            (require! (and descriptor-set binding
                           (identical? descriptor-set
                                       (get-in runtime
                                               [:source :typed-span-descriptors])))
                      "confirmed span descriptors did not reach exporter/query state")
            (trace/with-span
              [_ (sdk/tracer "minimal-embedded-app") span-name
               {:attributes {typed-attribute-key true}}])
            (require! (map? (deref (:received remote-peer)
                                   request-wait-ms ::not-received))
                      "remote exporter did not enter its deterministic stall")
            (require! (= [application-row]
                         (jdbc/fetch application
                                     "select id, state from application_state"))
                      "SQLite application state was not usable beside telemetry")

            (let [query
                  (embedded-query/start!
                   {:load! #(typed-span-rows runtime binding)
                    :interval-ms 60000
                    :timeout-ms query-timeout-ms
                    :stop-timeout-ms 5000
                    :max-rows 10})]
              (reset! query* query)
              (let [snapshot (await-ready query)
                    row (first (:rows snapshot))]
                (require! (= :ready (:status snapshot))
                          "bounded telemetry query did not become ready"
                          {:status (:status snapshot)})
                (require! (= {:span-name span-name
                              :attribute-key typed-attribute-key
                              :attribute-type :boolean
                              :attribute-value true
                              :attribute-location :span-attributes
                              :manifest-version 1}
                             (select-keys
                              row
                              [:span-name :attribute-key :attribute-type
                               :attribute-value :attribute-location
                               :manifest-version]))
                          (str "local typed export did not remain live while "
                               "the remote exporter was stalled")))

              (let [application-result (promise)
                    application-worker
                    (Thread.
                     #(deliver
                       application-result
                       (try
                         (http/get (:url application-peer)
                                   {:socket-timeout 10000})
                         :returned
                         (catch Throwable error (class error)))))]
                (reset! application-worker* application-worker)
                (.start application-worker)
                (require! (map? (deref (:received application-peer)
                                      request-wait-ms ::not-received))
                          "application HTTP request did not enter its stall")
                (let [status (embedded/status runtime)
                      rendered (pr-str status)]
                  (require! (= :open (:phase status))
                            "safe lifecycle status did not report the open owner")
                  (require! (safe-status? status)
                            "safe lifecycle status exceeded its closed scalar schema")
                  (require! (= 1 (get-in status
                                        [:span-pipelines :local
                                         :exported-span-count]))
                            "safe status did not report the independent local export")
                  (doseq [secret ["application-secret" "remote-secret"
                                  typed-attribute-key "authoritative"]]
                    (require! (not (.contains rendered secret))
                              "safe lifecycle status retained sensitive data")))
                (let [started (System/currentTimeMillis)]
                  (.interrupt application-worker)
                  (require! (= java.lang.InterruptedException
                               (deref application-result
                                      cancellation-wait-ms ::still-blocked))
                            "blocked application HTTP request did not cancel")
                  (require! (< (- (System/currentTimeMillis) started) 1500)
                            "application HTTP cancellation exceeded its bound")))

              (require! (= :closed (:status (embedded-query/stop! query)))
                        "bounded query helper did not retire")
              (require! (= {:phase :closed
                            :worker-stopped? true
                            :executor-shutdown? true}
                           @(:state query))
                        "bounded query helper did not close all owned work")
              (require! (= :closed (:status (embedded-query/stop! query)))
                        "bounded query helper stop was not idempotent"))

            (let [terminal (:terminal provider)
                  started (System/currentTimeMillis)
                  stopped (embedded/stop! runtime)
                  elapsed (- (System/currentTimeMillis) started)
                  terminal-outcome @terminal]
              (require! (= {:status :closed
                            :phase :closed
                            :telemetry
                            {:sdk {:ok? true}
                             :span-pipelines
                             {:local {:ok? true}
                              :remote {:ok? false
                                       :failure :returned-false}}}}
                           stopped)
                        "stalled remote did not fail closed beside local shutdown")
              (require! (< elapsed 3500)
                        "remote cancellation exceeded the SDK shutdown bound")
              (require! (and terminal-outcome
                             (nil? (sdk/tracer-provider))
                             (true? @(:shutdown? provider))
                             (closed? (:connection runtime)))
                        "SDK/source/checkpoint/connection shutdown was incomplete")
              (let [status (embedded/status runtime)]
                (require! (and (= :closed (:phase status))
                               (safe-status? status)
                               (= 1 (get-in status
                                           [:span-pipelines :remote
                                            :attempted-span-count]))
                               (= 1 (get-in status
                                           [:span-pipelines :remote
                                            :failed-span-count]))
                               (= 1 (get-in status
                                           [:span-pipelines :local
                                            :exported-span-count])))
                          "closed status did not retain bounded local/remote evidence"))
              (require! (= stopped (embedded/stop! runtime))
                        "embedded lifecycle stop was not idempotent")
              (require! (identical? terminal-outcome @terminal)
                        "SDK terminal action ran more than once")
              (require! (= 1 @(:requests remote-peer))
                        "remote shutdown retried or duplicated the stalled POST")
              (require! (throws? #(jdbc/fetch (:connection runtime)
                                             "select 1 as live"))
                        "Durable connection remained usable after close"))))

        (.close application)
        (require! (and (closed? application)
                       (throws? #(jdbc/fetch application "select 1 as live")))
                  "SQLite connection remained usable after close")
        (reset! application* nil))

      (require! (nil? (find-ns 'oscope.server))
                "minimal fixture loaded the server namespace")
      (require! (nil? (find-ns 'oscope.embedded.viewer))
                "minimal fixture loaded the viewer namespace")
      (println "minimal embedded native fixture: PASS")
      (finally
        (when-let [query @query*]
          (try (embedded-query/stop! query) (catch Throwable _ nil)))
        (when-let [runtime @runtime*]
          (try (embedded/stop! runtime) (catch Throwable _ nil)))
        (when-let [application @application*]
          (try (.close application) (catch Throwable _ nil)))
        (when-let [application-worker @application-worker*]
          (.interrupt application-worker)
          (.join application-worker 2000))
        (stop-stalled-peer! application-peer)
        (stop-stalled-peer! remote-peer)))))
