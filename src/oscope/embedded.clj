(ns oscope.embedded
  "One-owner, in-process OTel SDK, Durable chDB, and oscope composition."
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [oscope.live :as live]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def ^:private batch-option-keys
  #{:max-queue-size :max-export-batch-size :schedule-delay-ms})

(def ^:private remote-option-keys
  (into batch-option-keys
        #{:endpoint :traces-url :headers-env :timeout-ms :max-retries
          :insecure?}))

(defn- invalid! [message type & [data]]
  (throw (ex-info message
                  (merge {:oscope.embedded/error true :type type} data))))

(defn- positive-integer! [destination options option]
  (when (and (contains? options option)
             (not (and (integer? (get options option))
                       (pos? (get options option)))))
    (invalid! "oscope embedded span pipeline option must be a positive integer"
              ::invalid-span-pipelines
              {:destination destination :option option})))

(defn- batch-options! [destination options]
  (doseq [option batch-option-keys]
    (positive-integer! destination options option))
  (select-keys options batch-option-keys))

(defn- safe-http-url? [value]
  ;; OTLP credentials belong in environment-referenced headers. Keeping them
  ;; out of URL userinfo/query/fragment also keeps transport failure diagnostics
  ;; from acquiring a second credential-bearing surface.
  (boolean
   (and (string? value)
        (re-matches #"https?://[^\s/?#@]+(?:/[^\s?#]*)?" value))))

(defn- validate-span-pipelines! [span-pipelines]
  (when (some? span-pipelines)
    (when-not (and (map? span-pipelines)
                   (= #{:local :remote} (set (keys span-pipelines))))
      (invalid! "oscope embedded :span-pipelines requires exactly :local and :remote"
                ::invalid-span-pipelines))
    (doseq [destination [:local :remote]]
      (when-not (map? (get span-pipelines destination))
        (invalid! "oscope embedded span pipeline options must be maps"
                  ::invalid-span-pipelines {:destination destination})))
    (let [local (:local span-pipelines)
          remote (:remote span-pipelines)
          unknown-local (seq (remove batch-option-keys (keys local)))
          unknown-remote (seq (remove remote-option-keys (keys remote)))
          endpoint? (contains? remote :endpoint)
          traces-url? (contains? remote :traces-url)]
      (when unknown-local
        (invalid! "oscope embedded local span pipeline has unknown options"
                  ::invalid-span-pipelines {:destination :local}))
      (when unknown-remote
        (invalid! "oscope embedded remote span pipeline has unknown options"
                  ::invalid-span-pipelines {:destination :remote}))
      (when (= endpoint? traces-url?)
        (invalid! "oscope embedded remote span pipeline requires either :endpoint or :traces-url"
                  ::invalid-span-pipelines {:destination :remote}))
      ;; Never echo a URL: userinfo and query parameters may contain credentials.
      (when-not (safe-http-url?
                 (get remote (if endpoint? :endpoint :traces-url)))
        (invalid! "oscope embedded remote span pipeline requires a credential-free HTTP(S) URL"
                  ::invalid-span-pipelines {:destination :remote}))
      (when (and (contains? remote :headers-env)
                 (not (and (string? (:headers-env remote))
                           (re-matches #"[A-Za-z_][A-Za-z0-9_]*"
                                       (:headers-env remote)))))
        (invalid! "oscope embedded :headers-env must name an environment variable"
                  ::invalid-span-pipelines {:destination :remote}))
      (positive-integer! :remote remote :timeout-ms)
      (when (and (contains? remote :max-retries)
                 (not (and (integer? (:max-retries remote))
                           (not (neg? (:max-retries remote))))))
        (invalid! "oscope embedded :max-retries must be a non-negative integer"
                  ::invalid-span-pipelines
                  {:destination :remote :option :max-retries}))
      (when (and (contains? remote :insecure?)
                 (not (boolean? (:insecure? remote))))
        (invalid! "oscope embedded :insecure? must be boolean"
                  ::invalid-span-pipelines
                  {:destination :remote :option :insecure?}))
      (batch-options! :local local)
      (batch-options! :remote remote)))
  span-pipelines)

(defn- build-remote-exporter [options]
  ;; Credentials flow only into the private exporter. The caller-visible
  ;; lifecycle and named results never retain the environment name or value.
  (let [headers (when-let [environment (:headers-env options)]
                  (otlp/parse-headers (host/getenv environment)))]
    (try
      (otlp/exporter
       (cond-> (assoc (select-keys options [:endpoint :traces-url :timeout-ms
                                            :max-retries :insecure?])
                      :environment? false)
         (seq headers) (assoc :headers headers)))
      (catch Throwable _
        ;; The upstream exception includes the rejected URL. Replace it with a
        ;; closed error rather than retaining possible endpoint credentials.
        (invalid! "oscope embedded could not configure the remote span exporter"
                  ::invalid-span-pipelines {:destination :remote})))))

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

(defn- local-required-processor [pipelines results]
  (reify export/SpanProcessor
    (on-start [_ span parent-context]
      (export/on-start pipelines span parent-context))
    (on-end [_ span]
      (export/on-end pipelines span))
    (force-flush! [_]
      (let [outcome (export/force-flush-pipelines! pipelines)]
        (reset! results outcome)
        (true? (get-in outcome [:local :ok?]))))
    (shutdown! [_]
      (let [outcome (export/shutdown-pipelines! pipelines)]
        (reset! results outcome)
        (true? (get-in outcome [:local :ok?]))))))

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
    (:span-pipeline-shutdown state)
    (assoc :telemetry
           {:sdk (:sdk-shutdown state)
            :span-pipelines (:span-pipeline-shutdown state)})
    error (assoc :errors [error])))

(defn- sdk-operation! [operation sdk-handle pipeline-results]
  (try
    (let [ok? (boolean (operation sdk-handle))
          results (or @pipeline-results {})]
      {:ok? ok?
       :failure (when-not ok? :returned-false)
       :results results})
    (catch Throwable _
      {:ok? false
       :failure :threw
       :results (or @pipeline-results {})})))

(defn- stop-lifecycle!
  [{:keys [sdk-handle source connection checkpoint-on-close? state lock
           pipeline-results]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        ;; The application owns its ingress. It must stop producing telemetry
        ;; before calling stop!, then this boundary drains every SDK pipeline.
        (when-not (:sdk-stopped? @state)
          (if pipeline-results
            (let [{:keys [ok? failure results]}
                  (sdk-operation! sdk/shutdown! sdk-handle pipeline-results)]
              ;; SDK and processor shutdown are terminal exactly-once actions.
              ;; Retrying a failed result cannot rerun them, so retain the safe
              ;; outcome and continue releasing the query and Durable owners.
              (swap! state assoc
                     :sdk-shutdown (cond-> {:ok? ok?}
                                     failure (assoc :failure failure))
                     :span-pipeline-shutdown results
                     :sdk-stopped? true
                     :phase :retiring-oscope))
            ;; Preserve the established local-only contract, whose SDK owner
            ;; may still report a retryable nonterminal shutdown failure.
            (do
              (when-not (sdk/shutdown! sdk-handle)
                (throw (ex-info "oscope embedded OTel SDK did not stop"
                                {:oscope.embedded/error true
                                 :type ::sdk-shutdown-failed})))
              (swap! state assoc :sdk-stopped? true :phase :retiring-oscope))))
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
  `otel.sdk/init!`; this lifecycle supplies its own exporter and processors.
  Metrics default on and logs default off. When `:checkpoint-on-close?` is true
  (the default), shutdown compacts committed WAL into a new checkpoint.
  Optional `:typed-schema` is an operator-approved manifest and registry.

  Optional `:span-pipelines` must contain exactly `:local` and `:remote` maps.
  Each has independent bounded batch options. Remote OTLP/HTTP JSON accepts
  exactly one of `:endpoint` or `:traces-url`, plus `:timeout-ms`,
  `:max-retries`, `:insecure?`, and `:headers-env`. Headers cannot be supplied
  inline. The remote destination receives spans only; logs and metrics remain
  owned by the local chDB exporter.

  The caller must stop application ingress before calling `stop!`. The returned
  `:source` can be given to oscope UI handlers."
  [{:keys [db-spec sdk-options checkpoint-on-close? typed-schema span-pipelines]
    :or {sdk-options {} checkpoint-on-close? true}}]
  (when-not db-spec
    (invalid! "oscope embedded requires a Durable :db-spec" ::missing-db-spec))
  (when-not (map? sdk-options)
    (invalid! "oscope embedded :sdk-options must be a map" ::invalid-sdk-options))
  (when (contains? sdk-options :exporter)
    (invalid! "oscope embedded owns the SDK exporter" ::exporter-owned))
  (when (contains? sdk-options :span-processors)
    (invalid! "oscope embedded owns the SDK span processors"
              ::span-processors-owned))
  (when-not (boolean? checkpoint-on-close?)
    (invalid! "oscope embedded :checkpoint-on-close? must be boolean"
              ::invalid-checkpoint-on-close))
  (typed-schema/validate-options typed-schema)
  (validate-span-pipelines! span-pipelines)
  (when (or (sdk/tracer-provider) (sdk/meter-provider) (sdk/logger-provider))
    (invalid! "oscope embedded requires an unconfigured process OTel SDK"
              ::sdk-already-configured))
  (let [signals (sdk-signals sdk-options)
        ;; URL/header resolution is deliberately before JDBC and worker startup.
        remote-exporter (when span-pipelines
                          (build-remote-exporter (:remote span-pipelines)))
        connection (jdbc/connection db-spec)
        exporter* (atom nil)
        source* (atom nil)
        sdk-handle* (atom nil)
        pipelines* (atom nil)]
    (try
      (when (and typed-schema
                 (not= :writer (durable/connection-role connection)))
        (invalid! "oscope typed schema requires a Durable writer"
                  ::typed-schema-writer-required))
      (let [schema-context (typed-schema/install! connection typed-schema)
            _ (when schema-context
                (require-status! :checkpoint #{:committed :reconciled}
                                 (durable/checkpoint! connection)))
            exporter-options
            (typed-schema/exporter-options
             {:connection connection :signals signals :durable? true}
             schema-context)
            exporter (chdb-export/exporter exporter-options)
            _ (reset! exporter* exporter)
            pipelines (when span-pipelines
                        (export/independent-batch-pipelines
                         {:local {:exporter exporter
                                  :config (batch-options! :local
                                                          (:local span-pipelines))}
                          :remote {:exporter remote-exporter
                                   :config (batch-options! :remote
                                                          (:remote span-pipelines))}}))
            _ (reset! pipelines* pipelines)
            pipeline-results (when pipelines (atom nil))
            sdk-processor (when pipelines
                            (local-required-processor pipelines pipeline-results))
            source (live/open!
                    (cond-> {:connection connection :ensure-schema? false}
                      schema-context
                      (assoc :typed-span-descriptors
                             (:descriptor-set schema-context))))
            _ (reset! source* source)
            sdk-handle (sdk/init!
                        (cond-> (assoc sdk-options :exporter exporter)
                          sdk-processor (assoc :span-processors [sdk-processor])))
            _ (reset! sdk-handle* sdk-handle)]
        (when (:disabled? sdk-handle)
          (invalid! "oscope embedded cannot start while OTEL_SDK_DISABLED is true"
                    ::sdk-disabled))
        (let [internal
              (cond->
               {:db-spec db-spec
                :connection connection
                :exporter exporter
                :source source
                :sdk-handle sdk-handle
                :signals signals
                :checkpoint-on-close? checkpoint-on-close?
                :pipeline-results pipeline-results
                :pipelines pipelines
                :state (atom {:phase :open
                              :sdk-stopped? false
                              :oscope-retired? false
                              :persisted? false
                              :connection-closed? false})
                :lock (Object.)}
                schema-context
                (assoc :typed-span-descriptors
                       (:descriptor-set schema-context)))
              public-base (cond->
                           (select-keys internal
                                        [:db-spec :connection :exporter :source
                                         :signals :checkpoint-on-close?
                                         :typed-span-descriptors])
                            (nil? pipelines) (assoc :sdk-handle sdk-handle
                                                    :state (:state internal)
                                                    :lock (:lock internal)))
              stop-fn #(stop-lifecycle! internal)
              flush-fn (when pipelines
                         #(locking (:lock internal)
                            (if (= :open (:phase @(:state internal)))
                              (let [{:keys [ok? failure results]}
                                    (sdk-operation! sdk/force-flush!
                                                    sdk-handle
                                                    pipeline-results)]
                                {:sdk (cond-> {:ok? ok?}
                                        failure (assoc :failure failure))
                                 :span-pipelines results})
                              false)))
              stats-fn (when pipelines #(export/pipeline-stats pipelines))]
          (cond-> (assoc public-base :stop! stop-fn)
            pipelines (assoc :force-flush! flush-fn
                             :span-pipeline-stats stats-fn))))
      (catch Throwable error
        (when-let [sdk-handle @sdk-handle*]
          (try (sdk/shutdown! sdk-handle) (catch Throwable _ nil)))
        (when-let [pipelines @pipelines*]
          (try (export/shutdown-pipelines! pipelines) (catch Throwable _ nil)))
        (when-let [source @source*]
          (try (live/close! source) (catch Throwable _ nil)))
        (when (and @exporter* (nil? @sdk-handle*))
          (doseq [signal signals]
            (when (or (nil? @pipelines*) (not= signal :spans))
              (try (close-exporter-face! @exporter* signal)
                   (catch Throwable _ nil)))))
        (try (.close connection) (catch Throwable _ nil))
        (throw error)))))

(defn force-flush!
  "Drain the in-process SDK. Local-only lifecycles return the existing Boolean.
  Dual span lifecycles return a safe SDK marker and named pipeline results."
  [{:keys [sdk-handle state lock] :as lifecycle}]
  (if-let [flush-fn (:force-flush! lifecycle)]
    (flush-fn)
    (do
      (when-not (and sdk-handle state lock)
        (invalid! "invalid oscope embedded lifecycle" ::invalid-lifecycle))
      (locking lock
        (if (= :open (:phase @state))
          (sdk/force-flush! sdk-handle)
          false)))))

(defn span-pipeline-stats
  "Return bounded queue and drop counts for both span destinations."
  [lifecycle]
  (if-let [stats-fn (:span-pipeline-stats lifecycle)]
    (stats-fn)
    (invalid! "oscope embedded lifecycle has no dual span pipelines"
              ::no-span-pipelines)))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    (invalid! "invalid oscope embedded lifecycle" ::invalid-lifecycle)))
