(ns oscope.embedded
  "One-owner, in-process OTel SDK, Durable chDB, and oscope composition."
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [oscope.live :as live]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- sdk-signals [{:keys [metrics? logs?]
                     :or {metrics? true logs? false}}]
  (cond-> #{:spans}
    metrics? (conj :metrics)
    logs? (conj :logs)))

(defn- close-exporter-face! [exporter signal]
  (case signal
    :spans (export/shutdown-exporter! exporter)
    :metrics (export/shutdown-metric-exporter! exporter)
    :logs (logs/shutdown-log-exporter! exporter)))

(defn- require-status! [operation allowed result]
  (when-not (contains? allowed (:status result))
    (throw (ex-info (str "oscope embedded Durable " (name operation)
                         " did not confirm persistence")
                    {:oscope.embedded/error true
                     :type ::durability-unconfirmed
                     :operation operation
                     :status (:status result)})))
  true)

(defn- stop-result [state error]
  (cond-> {:status (if (= :closed (:phase state)) :closed :closing)
           :phase (:phase state)}
    error (assoc :errors [error])))

(defn- stop-lifecycle!
  [{:keys [sdk-handle source connection checkpoint-on-close? state lock]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        ;; The application owns its ingress. It must stop producing telemetry
        ;; before calling stop!, then this boundary drains every SDK pipeline.
        (when-not (:sdk-stopped? @state)
          (when-not (sdk/shutdown! sdk-handle)
            (throw (ex-info "oscope embedded OTel SDK did not stop"
                            {:oscope.embedded/error true
                             :type ::sdk-shutdown-failed})))
          (swap! state assoc :sdk-stopped? true :phase :retiring-oscope))
        (when-not (:oscope-retired? @state)
          (live/close! source)
          (swap! state assoc :oscope-retired? true :phase :persisting))
        (when-not (:persisted? @state)
          (if checkpoint-on-close?
            (require-status! :checkpoint #{:committed :reconciled}
                             (durable/checkpoint! connection))
            (require-status! :flush #{:empty :committed :reconciled}
                             (durable/flush! connection)))
          (swap! state assoc :persisted? true :phase :closing-connection))
        (when-not (:connection-closed? @state)
          (.close connection)
          (swap! state assoc :connection-closed? true :phase :closed))
        (stop-result @state nil)
        (catch Throwable error
          (stop-result @state error))))))

(defn start!
  "Start an in-process OTel SDK, Durable chDB writer, and oscope query source.

  Required `:db-spec` must open a Durable writer. `:sdk-options` are passed to
  `otel.sdk/init!`; this lifecycle supplies its own exporter. Metrics default on
  and logs default off, matching the SDK. When `:checkpoint-on-close?` is true
  (the default), shutdown compacts committed WAL into a new checkpoint.
  Optional `:typed-schema` is a closed operator-supplied approved manifest,
  registry backend, and optional event sink. Oscope binds all database effects
  to its owned connection, and checkpoints the schema before the exporter or
  SDK starts.

  The caller must stop application ingress before calling `stop!`. The returned
  `:source` can be given to oscope UI handlers, and ordinary instrumentation can
  use `otel.sdk/tracer`, `meter`, and `logger` without OTLP or HTTP encoding."
  [{:keys [db-spec sdk-options checkpoint-on-close? typed-schema]
    :or {sdk-options {} checkpoint-on-close? true}}]
  (when-not db-spec
    (throw (ex-info "oscope embedded requires a Durable :db-spec"
                    {:oscope.embedded/error true :type ::missing-db-spec})))
  (when-not (map? sdk-options)
    (throw (ex-info "oscope embedded :sdk-options must be a map"
                    {:oscope.embedded/error true :type ::invalid-sdk-options})))
  (when (contains? sdk-options :exporter)
    (throw (ex-info "oscope embedded owns the SDK exporter"
                    {:oscope.embedded/error true :type ::exporter-owned})))
  (when-not (boolean? checkpoint-on-close?)
    (throw (ex-info "oscope embedded :checkpoint-on-close? must be boolean"
                    {:oscope.embedded/error true
                     :type ::invalid-checkpoint-on-close})))
  (typed-schema/validate-options typed-schema)
  (when (or (sdk/tracer-provider) (sdk/meter-provider) (sdk/logger-provider))
    (throw (ex-info "oscope embedded requires an unconfigured process OTel SDK"
                    {:oscope.embedded/error true :type ::sdk-already-configured})))
  (let [signals (sdk-signals sdk-options)
        connection (jdbc/connection db-spec)
        exporter* (atom nil)
        source* (atom nil)
        sdk-handle* (atom nil)]
    (try
      (when (and typed-schema
                 (not= :writer (durable/connection-role connection)))
        (throw (ex-info "oscope typed schema requires a Durable writer"
                        {:oscope.embedded/error true
                         :type ::typed-schema-writer-required})))
      ;; Typed DDL is reachable only through the explicit operator-authorized
      ;; installer. It owns the base schema and publishes descriptors only
      ;; after active persistence and physical observation are confirmed.
      (let [schema-context (typed-schema/install! connection typed-schema)
            _ (when schema-context
                (require-status! :checkpoint #{:committed :reconciled}
                                 (durable/checkpoint! connection)))
            exporter-options
            ;; With no typed schema this helper returns the map unchanged, so
            ;; the exporter retains its default base-schema ownership.
            (typed-schema/exporter-options
             {:connection connection :signals signals :durable? true}
             schema-context)
            exporter (chdb-export/exporter exporter-options)
            _ (reset! exporter* exporter)
            source (live/open!
                    (cond-> {:connection connection :ensure-schema? false}
                      schema-context
                      (assoc :typed-span-descriptors
                             (:descriptor-set schema-context))))
            _ (reset! source* source)
            sdk-handle (sdk/init! (assoc sdk-options :exporter exporter))
            _ (reset! sdk-handle* sdk-handle)]
        (when (:disabled? sdk-handle)
          (throw (ex-info "oscope embedded cannot start while OTEL_SDK_DISABLED is true"
                          {:oscope.embedded/error true :type ::sdk-disabled})))
        (let [lifecycle
              (cond->
               {:db-spec db-spec
                :connection connection
                :exporter exporter
                :source source
                :sdk-handle sdk-handle
                :signals signals
                :checkpoint-on-close? checkpoint-on-close?
                :state (atom {:phase :open
                              :sdk-stopped? false
                              :oscope-retired? false
                              :persisted? false
                              :connection-closed? false})
                :lock (Object.)}
                schema-context
                (assoc :typed-span-descriptors
                       (:descriptor-set schema-context)))]
          (assoc lifecycle :stop! #(stop-lifecycle! lifecycle))))
      (catch Throwable error
        (when-let [sdk-handle @sdk-handle*]
          (try (sdk/shutdown! sdk-handle) (catch Throwable _ nil)))
        (when-let [source @source*]
          (try (live/close! source) (catch Throwable _ nil)))
        (when (and @exporter* (nil? @sdk-handle*))
          (doseq [signal signals]
            (try (close-exporter-face! @exporter* signal)
                 (catch Throwable _ nil))))
        (try (.close connection) (catch Throwable _ nil))
        (throw error)))))

(defn force-flush!
  "Drain the in-process SDK. Each non-empty exported batch is Durable before
  this returns true. Returns false once shutdown has started."
  [{:keys [sdk-handle state lock]}]
  (when-not (and sdk-handle state lock)
    (throw (ex-info "invalid oscope embedded lifecycle"
                    {:oscope.embedded/error true :type ::invalid-lifecycle})))
  (locking lock
    (if (= :open (:phase @state))
      (sdk/force-flush! sdk-handle)
      false)))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    (throw (ex-info "invalid oscope embedded lifecycle"
                    {:oscope.embedded/error true :type ::invalid-lifecycle}))))
