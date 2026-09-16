(ns oscope.server
  "Standalone loopback OTLP/HTTP receiver and oscope viewer composition."
  (:require [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.config :as config]
            [oscope.error :as error]
            [oscope.http-app :as http-app]
            [oscope.http-executor :as http-executor]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.readiness :as readiness]
            [oscope.typed-schema :as typed-schema]
            [oscope.ui.events :as events]
            [oscope.ui.workbench :as workbench]
            [oscope.ui.visualization-editor :as visualization-editor]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.otlp.http-receiver :as receiver]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def default-host http-app/default-host)
(def default-port http-app/default-port)
(def default-db-spec "chdb:./oscope-data")
(def default-http-workers http-app/default-http-workers)
(def default-http-queue-capacity http-app/default-http-queue-capacity)

(defn- validate-http-executor-options!
  [http-workers http-queue-capacity]
  (when-not (and (integer? http-workers) (pos? http-workers))
    (throw (ex-info "oscope HTTP workers must be positive"
                    {:oscope.server/error true
                     :http-workers http-workers})))
  (when-not (and (integer? http-queue-capacity)
                 (pos? http-queue-capacity))
    (throw (ex-info "oscope HTTP queue capacity must be positive"
                    {:oscope.server/error true
                     :http-queue-capacity http-queue-capacity})))
  true)

(defn handler
  "Compose receiver and viewer handlers without adding instrumentation.

  Absence of an OTel SDK, tracer, logger, or middleware in this namespace makes
  collector feedback impossible by construction."
  [options]
  (http-app/handler
   (cond-> options
     (:otlp-handler options) (assoc :otlp-paths receiver/receiver-paths))))

(defn- close-face! [face exporter]
  (let [closed? (case face
                  :spans (export/shutdown-exporter! exporter)
                  :logs (logs/shutdown-log-exporter! exporter)
                  :metrics (export/shutdown-metric-exporter! exporter))]
    (when-not closed?
      (throw (ex-info "oscope exporter face did not close"
                      {:oscope.server/error true :face face})))
    true))

(defn- lifecycle-operation [phase]
  (case phase
    :open :stop-ingress
    :stopping-http-executor :stop-http-executor
    :retiring-oscope :close-source
    :closing-exporters :close-exporter
    :closing-connection :close-connection
    :lifecycle))

(defn- stop-result [state failure]
  (cond-> {:status (if (= :closed (:phase state)) :closed :closing)
           :phase (:phase state)}
    failure (assoc :errors [failure])))

(defn- require-durability-status! [operation allowed result]
  (let [status (:status result)]
    (when-not (contains? allowed status)
      (throw (ex-info (str "oscope Durable " (name operation)
                           " did not confirm persistence")
                      {:oscope.server/error true
                       :type ::durability-unconfirmed
                       :operation operation
                       :status status})))
    true))

(defn- complete-durability-boundary!
  [durability connection batches-since-checkpoint lock]
  (locking lock
    (let [checkpoint-every (:checkpoint-every-batches durability)
          next-count (inc @batches-since-checkpoint)
          checkpoint? (and checkpoint-every
                           (= checkpoint-every next-count))
          operation (if checkpoint? :checkpoint :flush)
          allowed (if checkpoint?
                    #{:committed :reconciled}
                    #{:empty :committed :reconciled})
          result ((get durability (if checkpoint?
                                    :checkpoint!
                                    :flush!))
                  connection)]
      ;; Advance cadence only after the selected persistence operation is
      ;; confirmed. A thrown or unconfirmed checkpoint remains due, so the
      ;; next admitted batch cannot silently fall back to another WAL flush.
      (require-durability-status! operation allowed result)
      (reset! batches-since-checkpoint (if checkpoint? 0 next-count))
      true)))

(defn- stop-lifecycle!
  [{:keys [server http-executor source exporter connection state lock
           readiness-handle]}]
  (locking lock
    (if (= :closed (:phase @state))
      (readiness/finish-stop! readiness-handle (stop-result @state nil))
      (try
        (readiness/terminal! readiness-handle :stopping)
        (when-not (:ingress-stopped? @state)
          (http/stop-server server)
          (swap! state assoc :ingress-stopped? true
                 :phase :stopping-http-executor))
        (when-not (:http-executor-stopped? @state)
          (http-executor/stop! http-executor)
          (swap! state assoc :http-executor-stopped? true
                 :phase :retiring-oscope))
        (when-not (:oscope-retired? @state)
          (live/close! source)
          (swap! state assoc :oscope-retired? true :phase :closing-exporters))
        (doseq [face [:spans :logs :metrics]]
          (when-not (contains? (:closed-faces @state) face)
            (close-face! face exporter)
            (swap! state update :closed-faces conj face)))
        (swap! state assoc :phase :closing-connection)
        (when-not (:connection-closed? @state)
          (.close connection)
          (swap! state assoc :connection-closed? true :phase :closed))
        (readiness/finish-stop! readiness-handle (stop-result @state nil))
        (catch Throwable error
          (let [current @state]
            (stop-result current
                         (error/lifecycle-failure
                          (lifecycle-operation (:phase current)) error))))))))

(defn start!
  "Start one loopback server backed by one shared embedded chDB connection.

  `:port` may be zero for an ephemeral test port. jolt-http currently binds
  loopback at the transport layer, so `:host` deliberately accepts only
  127.0.0.1. Optional `:typed-schema` is a closed operator-supplied approved
  manifest, registry backend, and optional event sink; Oscope binds all
  database effects to its owned connection. The returned
  `:stop!` is idempotent and retries the first incomplete ownership boundary
  on each call."
  ([] (start! {}))
  ([{:keys [host port db-spec durability http-workers http-queue-capacity
            typed-schema readiness]
     :or {host default-host port default-port db-spec default-db-spec
          http-workers default-http-workers
          http-queue-capacity default-http-queue-capacity}}]
   (when-not (= default-host host)
     (throw (ex-info "oscope standalone receiver must bind to 127.0.0.1"
                     {:oscope.server/error true :host host})))
   (when-not (and (integer? port) (<= 0 port 65535))
     (throw (ex-info "oscope port must be between 0 and 65535"
                     {:oscope.server/error true :port port})))
   (validate-http-executor-options! http-workers http-queue-capacity)
   (typed-schema/validate-options typed-schema)
   (when (and durability
              (not (and (map? durability)
                        (ifn? (:checkpoint! durability))
                        (ifn? (:flush! durability))
                        (or (nil? (:checkpoint-every-batches durability))
                            (and (integer?
                                  (:checkpoint-every-batches durability))
                                 (pos?
                                  (:checkpoint-every-batches durability)))))))
     (throw (ex-info "oscope durability requires checkpoint! and flush! functions"
                     {:oscope.server/error true :type ::invalid-durability})))
   (let [readiness-handle (readiness/prepare! readiness (if durability :durable :local))
         conn (try (jdbc/connection db-spec)
                   (catch Throwable cause
                     (if readiness-handle
                       (readiness/fail-startup! readiness-handle cause [])
                       (throw cause))))
         exporter* (atom nil)
         source* (atom nil)
         server* (atom nil)
         http-executor* (atom nil)
         batches-since-checkpoint (atom 0)
         durability-lock (Object.)
         authority* (atom (when (pos? port) (str host ":" port)))]
     (try
       ;; Typed DDL is reachable only through this explicit operator-authorized
       ;; startup boundary, before an exporter or ingress can observe it.
       (let [schema-context (typed-schema/install! conn typed-schema)
             owned-http-executor
             (http-executor/start! {:workers http-workers
                                    :queue-capacity http-queue-capacity})
             _ (reset! http-executor* owned-http-executor)
             exporter
             (chdb-export/exporter
              ;; The no-manifest path remains unchanged: create-schema? is
              ;; absent and the exporter applies its default base migrations.
              (typed-schema/exporter-options
               {:connection conn :signals #{:spans :logs :metrics}}
               schema-context))
             _ (reset! exporter* exporter)
             ;; Durable mode checkpoints base and typed schema changes before
             ;; ingress can become reachable. Ordinary local mode has no
             ;; Durable promise and retains its existing startup behavior.
             _ (when durability
                 (require-durability-status!
                  :checkpoint #{:committed :reconciled}
                  ((:checkpoint! durability) conn)))
             source
             (live/open!
              (typed-schema/source-options
               {:connection conn :ensure-schema? false}
               schema-context))
             _ (reset! source* source)
             editor-handler (visualization-editor/handler source)
             app-handler (handler {:otlp-handler
                                   (if durability
                                     (otlp/handler
                                      exporter
                                      {:after-success!
                                       #(complete-durability-boundary!
                                         durability conn
                                         batches-since-checkpoint
                                         durability-lock)})
                                     (otlp/handler exporter))
                                   :workbench-handler
                                   (workbench/handler conn)
                                   :events-handler (events/handler conn)
                                   :oscope-handler
                                   (web/handler
                                    source
                                    {:visualization-editor-path
                                     (visualization-editor/plotje-path
                                      visualization-editor/default-path)})
                                   :visualization-editor-handler editor-handler
                                   :authority #(deref authority*)})
             ;; The admission wrapper is borrowed by jolt-http. It owns the
             ;; modeled ThreadPoolExecutor beneath it, which this lifecycle
             ;; stops only after jolt-http has retired ingress and drained all
             ;; admitted handlers.
             server (http/run-server
                     app-handler :port port :server-name host
                     :reuse-address? true
                     :executor (:executor owned-http-executor))
             _ (reset! server* server)
             _ (reset! authority* (str host ":" (:port server)))
             lifecycle
             (cond->
              {:host host :port (:port server) :db-spec db-spec
               :connection conn :exporter exporter :source source
               :server server
               :readiness-handle readiness-handle
               :http-executor owned-http-executor
               :state (atom {:phase :open :ingress-stopped? false
                             :http-executor-stopped? false
                             :oscope-retired? false :closed-faces #{}
                             :connection-closed? false})
               :lock (Object.)}
               schema-context
               (merge (typed-schema/descriptor-options schema-context)))
             _ (readiness/ready! readiness-handle host (:port server)
                                 (str "http://" host ":" (:port server)
                                      workbench/default-path))]
         (assoc lifecycle :stop! #(stop-lifecycle! lifecycle)))
       (catch Throwable error
         (reset! authority* nil)
         (if readiness-handle
           (readiness/fail-startup!
            readiness-handle error
            (concat
             (when-let [listener @server*]
               [[:stop-listener #(http/stop-server listener)]])
             (when-let [executor @http-executor*]
               [[:stop-http-executor #(http-executor/stop! executor)]])
             (when-let [source @source*]
               [[:retire-source #(live/close! source)]])
             (when-let [exporter @exporter*]
               (mapv (fn [face] [face #(close-face! face exporter)])
                     [:spans :logs :metrics]))
             [[:close-connection #(.close conn)]]))
           (do
             (when-let [server @server*]
               (try (http/stop-server server) (catch Throwable _ nil)))
             (when-let [owned-http-executor @http-executor*]
               (try (http-executor/stop! owned-http-executor)
                    (catch Throwable _ nil)))
             (when-let [source @source*]
               (try (live/close! source) (catch Throwable _ nil)))
             (when-let [exporter @exporter*]
               (doseq [face [:spans :logs :metrics]]
                 (try (close-face! face exporter) (catch Throwable _ nil))))
             (try (.close conn) (catch Throwable _ nil))
             (throw error))))))))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    {:status :closed :phase :closed}))

(defn http-executor-env-options [environment]
  (let [resolved (config/resolve-config
                  [[:environment (config/environment-layer environment)]])
        server (:server (:config resolved))]
    {:http-workers (:http-workers server)
     :http-queue-capacity (:http-queue-capacity server)}))

(defn env-options []
  (let [names ["OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_CHDB_SPEC"
               "OSCOPE_HTTP_WORKERS" "OSCOPE_HTTP_QUEUE_CAPACITY"]
        environment (into {}
                          (map (fn [name] [name (System/getenv name)]))
                          names)
        resolved (config/resolve-config
                  [[:environment (config/environment-layer environment)]])
        document (:config resolved)]
    {:host (get-in document [:server :host])
     :port (get-in document [:server :port])
     :http-workers (get-in document [:server :http-workers])
     :http-queue-capacity (get-in document [:server :http-queue-capacity])
     :db-spec (config/storage->db-spec (:storage document))}))
