(ns oscope.embedded
  "One-owner, in-process OTel SDK, Durable chDB, and oscope composition."
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [oscope.error :as error]
            [oscope.live :as live]
            [oscope.readiness :as readiness]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.lifecycle :as sdk-lifecycle]
            [otel.sdk.logs :as logs]))

(def ^:private batch-option-keys
  #{:max-queue-size :max-export-batch-size :schedule-delay-ms})

(def ^:private remote-option-keys
  (into batch-option-keys
        #{:endpoint :traces-url :headers-env :timeout-ms :max-retries
          :insecure?}))

(def ^:private lifecycle-phases
  #{:open :retiring-oscope :persisting :closing-connection :closed})

(def ^:private pipeline-counter-keys
  [:queue-size :attempted-span-count :exported-span-count
   :failed-span-count :dropped-count])

(def ^:private unavailable-pipeline-status
  (assoc (zipmap pipeline-counter-keys (repeat :unavailable))
         :availability :unavailable))

(def ^:private max-status-counter 9223372036854775807)

;; Durable's public observation uses the same cross-runtime safe-integer limit
;; as its manifest sequence.  Keep this adapter closed over that documented
;; scalar boundary: accepting an arbitrary integer would turn a forged value
;; into a health claim the Durable capability itself cannot make.
(def ^:private max-durable-sequence 9007199254740991)

(def ^:private unavailable-durable-observation
  {:availability :unavailable})

(def ^:private durable-observation-keys
  #{:availability :role :state :view-current? :confirmed-boundary
    :confirmed-sequence :last-successful-persistence})

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
        (true? (get-in outcome [:local :ok?]))))
    sdk-lifecycle/SettlementWitness
    (settlement-status [_]
      ;; The adapter owns no resource operations beyond those delegated to
      ;; the maintained pipelines. Delivery policy does not weaken ownership.
      (sdk-lifecycle/component-settlement pipelines))))

(defn- require-status! [operation allowed result]
  (when-not (contains? allowed (:status result))
    (throw (ex-info (str "oscope embedded Durable " (name operation)
                         " did not confirm persistence")
                    {:oscope.embedded/error true
                     :type ::durability-unconfirmed
                     :operation operation
                     :status (:status result)})))
  true)

(defn- lifecycle-operation [phase checkpoint-on-close?]
  (case phase
    :open :shutdown-sdk
    :retiring-oscope :close-source
    :persisting (if checkpoint-on-close? :checkpoint :flush)
    :closing-connection :close-connection
    :lifecycle))

(defn- stop-result [state failure]
  (cond-> {:status (if (= :closed (:phase state)) :closed :closing)
           :phase (:phase state)}
    (or (:span-pipeline-shutdown state)
        (false? (get-in state [:sdk-shutdown :ok?])))
    (assoc :telemetry
           (cond-> {:sdk (:sdk-shutdown state)}
             (:span-pipeline-shutdown state)
             (assoc :span-pipelines (:span-pipeline-shutdown state))))
    (and (:sdk-settlement state)
         (not= :confirmed (get-in state [:sdk-settlement :quiescence])))
    (assoc :settlement (:sdk-settlement state))
    failure (assoc :errors [failure])))

(defn- safe-counter? [value]
  (and (integer? value) (<= 0 value max-status-counter)))

(defn- safe-durable-sequence? [value]
  (and (integer? value) (<= 0 value max-durable-sequence)))

(defn- valid-durable-observation? [observation]
  ;; This mirrors the closed public contract of
  ;; jdbc.chdb.durable/persistence-observation.  Do not pass through unknown
  ;; keys: an embedded health surface must not acquire backend, head, dbspec,
  ;; payload, exception, or future unreviewed capability fields by accident.
  (and (map? observation)
       (= durable-observation-keys (set (keys observation)))
       (= :available (:availability observation))
       (contains? #{:writer :reader} (:role observation))
       (contains? #{:recovered :pending :unconfirmed :confirmed :snapshot}
                  (:state observation))
       (boolean? (:view-current? observation))
       (contains? #{:recovered :wal :checkpoint}
                  (:confirmed-boundary observation))
       (safe-durable-sequence? (:confirmed-sequence observation))
       (contains? #{:unavailable :wal :checkpoint}
                  (:last-successful-persistence observation))
       (case (:role observation)
         :reader
         (and (= :snapshot (:state observation))
              (false? (:view-current? observation))
              (= :recovered (:confirmed-boundary observation))
              (= :unavailable (:last-successful-persistence observation)))
         :writer
         (and (not= :snapshot (:state observation))
              (= (:view-current? observation)
                 (contains? #{:recovered :confirmed} (:state observation)))
              ;; Durable retains the last confirmed boundary while a later
              ;; mutation is pending or unconfirmed.  That relation remains
              ;; meaningful in every available writer state: accepting a
              ;; mismatched pair would let forged stale evidence look like a
              ;; valid health observation merely because it was not current.
              (case (:confirmed-boundary observation)
                :recovered (= :unavailable
                              (:last-successful-persistence observation))
                (:wal :checkpoint)
                (= (:confirmed-boundary observation)
                   (:last-successful-persistence observation))
                false)
              (or (not= :confirmed (:state observation))
                  (contains? #{:wal :checkpoint}
                             (:confirmed-boundary observation)))
              (if (= :recovered (:state observation))
                (and (= :recovered (:confirmed-boundary observation))
                     (= :unavailable (:last-successful-persistence observation)))
                true)))))

(defn- project-durable-observation
  "Project only the closed Durable persistence-observation vocabulary.

  This is deliberately pure.  It neither owns nor touches a JDBC connection,
  and it returns the sole unavailable value for malformed or forged input."
  [observation]
  (if (valid-durable-observation? observation)
    (select-keys observation durable-observation-keys)
    unavailable-durable-observation))

(defn- observe-durable-observation [connection]
  ;; The public chDB capability is a zero-I/O projection, but its extension
  ;; boundary can still reject a closed/non-Durable connection.  Exceptions
  ;; must not escape a health endpoint or retain their contents.
  (try
    (project-durable-observation (durable/persistence-observation connection))
    (catch Throwable _ unavailable-durable-observation)))

(defn- safe-pipeline-status [stats]
  (if (and (map? stats)
           (every? #(safe-counter? (get stats %)) pipeline-counter-keys))
    (assoc (select-keys stats pipeline-counter-keys)
           :availability :available)
    unavailable-pipeline-status))

(defn- lifecycle-status [state pipelines]
  (let [pipeline-stats
        (when pipelines
          (try
            (export/pipeline-stats pipelines)
            (catch Throwable _ nil)))]
    {:oscope.embedded.status/version 1
     :phase (let [phase (:phase @state)]
              (if (contains? lifecycle-phases phase) phase :unknown))
     :span-pipelines
     {:mode (if pipelines :independent :direct-local)
      :local (safe-pipeline-status (get pipeline-stats :local))
      :remote (safe-pipeline-status (get pipeline-stats :remote))}
     ;; jolt-chdb does not yet expose a public connection-status capability.
     ;; Reporting a guessed generation or freshness would make a health check
     ;; stronger than the persistence evidence available at this boundary.
     :durable {:view-current? :unavailable
               :last-successful-persistence :unavailable}}))

(defn- lifecycle-status-v2 [state lock pipelines connection]
  ;; Stop! holds the same lock around phase transitions and connection close.
  ;; Thus an observer either samples an owned open connection or returns the
  ;; terminal unavailable value; it never calls into Durable after close.
  (locking lock
    (let [pipeline-stats
          (when pipelines
            (try
              (export/pipeline-stats pipelines)
              (catch Throwable _ nil)))
          phase (let [phase (:phase @state)]
                  (if (contains? lifecycle-phases phase) phase :unknown))]
      {:oscope.embedded.status/version 2
       :phase phase
       :span-pipelines
       {:mode (if pipelines :independent :direct-local)
        :local (safe-pipeline-status (get pipeline-stats :local))
        :remote (safe-pipeline-status (get pipeline-stats :remote))}
       :durable (if (= :open phase)
                  (observe-durable-observation connection)
                  unavailable-durable-observation)})))

(defn- sdk-operation! [operation sdk-handle pipeline-results]
  (try
    (let [ok? (boolean (operation sdk-handle))
          results (or (some-> pipeline-results deref) {})]
      {:ok? ok?
       :failure (when-not ok? :returned-false)
       :results results})
    (catch Throwable _
      {:ok? false
       :failure :threw
       :results (or (some-> pipeline-results deref) {})})))

(defn- sdk-settlement [handle]
  (let [status (try (sdk/shutdown-status handle) (catch Throwable _ nil))
        confirmed? (and (= 1 (:otel.sdk.shutdown-status/version status))
                        (every? #(= :confirmed (get status %))
                                [:quiescence :sdk-quiescence :exporter-quiescence]))]
    ;; Copy only the closed proof factors, never a custom witness payload.
    {:sdk-quiescence (if (= :confirmed (:sdk-quiescence status)) :confirmed :unconfirmed)
     :exporter-quiescence (if (= :confirmed (:exporter-quiescence status)) :confirmed :unconfirmed)
     :quiescence (if confirmed? :confirmed :unconfirmed)}))

(defn- stop-lifecycle!
  [{:keys [sdk-handle source connection checkpoint-on-close? state lock
           pipeline-results]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        ;; The application owns its ingress. It must stop producing telemetry
        ;; before calling stop!, then this boundary drains every SDK pipeline.
        (when-not (:sdk-shutdown-observed? @state)
            (let [{:keys [ok? failure results]}
                  (sdk-operation! sdk/shutdown! sdk-handle pipeline-results)]
              ;; Cache delivery once; subsequent stop calls refresh only the
              ;; public ownership witness, never the terminal action.
              (swap! state assoc
                     :sdk-shutdown (cond-> {:ok? ok?}
                                     failure (assoc :failure failure))
                     :span-pipeline-shutdown (when pipeline-results results)
                     :sdk-shutdown-observed? true)))
        (when-not (:sdk-stopped? @state)
          (let [settlement (sdk-settlement sdk-handle)]
            (swap! state assoc :sdk-settlement settlement)
            (when-not (= :confirmed (:quiescence settlement))
              (throw (ex-info "oscope embedded SDK ownership is unconfirmed"
                              {:oscope.embedded/error true
                               :type ::sdk-settlement-unconfirmed})))
            (swap! state assoc :sdk-stopped? true :phase :retiring-oscope)))
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
          (let [current @state]
            (stop-result current
                         (error/lifecycle-failure
                          (lifecycle-operation (:phase current)
                                               checkpoint-on-close?)
                          error))))))))

(def ^:private maintained-pipeline-constructor export/independent-batch-pipelines)
(def ^:private maintained-sdk-constructor sdk/init!)

(defn- rollback-startup!
  [original {:keys [connection exporter source sdk-handle pipelines signals
                    remote-exporter sdk-init-entered? pipelines-init-entered?
                    sdk-receipt pipeline-receipt sdk-error pipeline-error
                    pipelines-maintained? sdk-maintained?]}]
  (let [terminal (atom #{}) face-results (atom {})
        once! (fn [key action]
                (when-not (contains? @terminal key)
                  ;; Retire exactly once even if the terminal callback throws.
                  (swap! terminal conj key)
                  (try (action) (catch Throwable _ nil))))
        confirmed! (fn [confirmed?]
                     (when-not confirmed?
                       (invalid! "oscope embedded startup ownership is unconfirmed"
                                 ::startup-settlement-unconfirmed)))
        constructor-confirmed? (fn [receipt error]
                                 (= :confirmed
                                    (:quiescence
                                     (sdk-lifecycle/construction-failure-status
                                      receipt error))))
        pipeline-face (fn [resource]
                        (cond
                          (and pipelines-init-entered? (not pipelines-maintained?))
                          {:ownership :unknown}
                          pipelines {:ownership :sdk-owned}
                          pipelines-init-entered?
                          (export/construction-face-status pipeline-receipt
                                                           pipeline-error resource :spans)
                          :else {:ownership :unacquired}))
        sdk-face (fn [signal]
                   (cond
                     (and sdk-init-entered? (not sdk-maintained?))
                     {:ownership :unknown}
                     (and sdk-handle (not (:disabled? sdk-handle)))
                     {:ownership :sdk-owned}
                     sdk-handle {:ownership :unacquired}
                     sdk-init-entered?
                     (sdk/construction-face-status sdk-receipt sdk-error exporter signal)
                     :else {:ownership :unacquired}))
        faces (cond-> (mapv (fn [signal] {:key [:local signal]
                                        :resource exporter :signal signal})
                            (if exporter signals []))
                remote-exporter (conj {:key [:remote :spans]
                                       :resource remote-exporter :signal :spans}))]
    ;; Reuse the existing serialized, opaque retry capability. Its diagnostic
    ;; result never contains original errors, resources or custom witness data.
    (readiness/fail-startup!
     nil original
     [[:settle-startup-owners
       (fn []
         (when sdk-handle (once! :sdk #(sdk/shutdown! sdk-handle)))
         (when (and pipelines
                    (= :not-started
                       (:terminal (sdk-lifecycle/component-settlement pipelines))))
           (once! :pipelines #(export/shutdown-pipelines! pipelines)))
         (when (and sdk-init-entered? (nil? sdk-handle))
           (once! :sdk-construction #(sdk-lifecycle/retire-construction! sdk-receipt))
           (confirmed! (constructor-confirmed? sdk-receipt sdk-error)))
         (when (and pipelines-init-entered? (nil? pipelines))
           (once! :pipeline-construction
                  #(sdk-lifecycle/retire-construction! pipeline-receipt))
           (confirmed! (constructor-confirmed? pipeline-receipt pipeline-error)))
         ;; A disabled SDK's empty aggregate does NOT cover acquired pipelines.
         (when sdk-handle
           (confirmed! (= :confirmed (:quiescence (sdk-settlement sdk-handle)))))
         (when pipelines
           (confirmed! (= :confirmed
                          (:quiescence (sdk-lifecycle/component-settlement pipelines))))))]
      [:settle-exporter-faces
       (fn []
         ;; A returned handle or matching failure receipt covers observable
         ;; owners, not extra input users acquired by a foreign root wrapper.
         (when sdk-init-entered? (confirmed! sdk-maintained?))
         ;; Only a positively accounted maintained span handoff can justify
         ;; pre-SDK span-only use. Missing other-signal claims grant NOTHING.
         (when pipelines-init-entered?
           (confirmed! (contains? #{:sdk-owned :unacquired}
                                  (:ownership (pipeline-face exporter)))))
         (doseq [{:keys [key resource signal]} faces]
           (let [ownership (:ownership
                            (if (or (= :remote (first key))
                                    (and (= :spans signal) pipelines-init-entered?))
                              (pipeline-face resource) (sdk-face signal)))]
             (confirmed! (contains? #{:sdk-owned :unacquired} ownership))
             (when (= :unacquired ownership)
               (when-not (contains? @face-results key)
                 (swap! face-results assoc key
                        (try (true? (close-exporter-face! resource signal))
                             (catch Throwable _ false))))
               (confirmed! (or (true? (get @face-results key))
                               (= :confirmed
                                  (:quiescence
                                   (sdk-lifecycle/component-settlement resource)))))))))]
      [:retire-source #(when source (live/close! source))]
      [:close-connection #(.close connection)]])))

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
  (when (contains? sdk-options :construction-receipt)
    (invalid! "oscope embedded owns construction receipts" ::receipt-owned))
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
        sdk-init-entered? (atom false)
        pipelines-init-entered? (atom false)
        pipelines-maintained? (atom false)
        sdk-maintained? (atom false)
        sdk-receipt (sdk-lifecycle/construction-receipt)
        pipeline-receipt (sdk-lifecycle/construction-receipt)
        sdk-error (atom nil)
        pipeline-error (atom nil)
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
                        (let [destinations
                              {:local {:exporter exporter
                                       :config (batch-options! :local
                                                               (:local span-pipelines))}
                               :remote {:exporter remote-exporter
                                        :config (batch-options! :remote
                                                                (:remote span-pipelines))}}
                              constructor export/independent-batch-pipelines]
                          (reset! pipelines-maintained?
                                  (identical? constructor maintained-pipeline-constructor))
                          (reset! pipelines-init-entered? true)
                          (try (constructor destinations pipeline-receipt)
                               (catch Throwable error
                                 (reset! pipeline-error error) (throw error)))))
            _ (reset! pipelines* pipelines)
            pipeline-results (when pipelines (atom nil))
            sdk-processor (when pipelines
                            (local-required-processor pipelines pipeline-results))
            source (live/open!
                    (typed-schema/source-options
                     {:connection connection :ensure-schema? false}
                     schema-context))
            _ (reset! source* source)
            sdk-handle (let [constructor sdk/init!]
                         (reset! sdk-maintained?
                                 (identical? constructor maintained-sdk-constructor))
                         (reset! sdk-init-entered? true)
                         (try
                           (constructor
                            (cond-> (assoc sdk-options :exporter exporter
                                                      :construction-receipt sdk-receipt)
                              sdk-processor (assoc :span-processors [sdk-processor])))
                           (catch Throwable error
                             (reset! sdk-error error) (throw error))))
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
                (merge (typed-schema/descriptor-options schema-context)))
              public-base (cond->
                           (select-keys internal
                                        [:db-spec :connection :exporter :source
                                         :signals :checkpoint-on-close?
                                         :typed-span-descriptors
                                         :typed-log-descriptors])
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
              stats-fn (when pipelines #(export/pipeline-stats pipelines))
              status-fn #(lifecycle-status (:state internal) pipelines)
              status-v2-fn #(lifecycle-status-v2 (:state internal) (:lock internal)
                                                pipelines connection)]
          (cond-> (assoc public-base :stop! stop-fn :status-fn status-fn
                                      :status-v2-fn status-v2-fn)
            pipelines (assoc :force-flush! flush-fn
                             :span-pipeline-stats stats-fn))))
      (catch Throwable error
        (rollback-startup!
         error {:connection connection :exporter @exporter* :source @source*
                :sdk-handle @sdk-handle* :pipelines @pipelines* :signals signals
                :remote-exporter remote-exporter
                :sdk-receipt sdk-receipt :pipeline-receipt pipeline-receipt
                :sdk-error @sdk-error :pipeline-error @pipeline-error
                :pipelines-maintained? @pipelines-maintained?
                :sdk-maintained? @sdk-maintained?
                :sdk-init-entered? @sdk-init-entered?
                :pipelines-init-entered? @pipelines-init-entered?})))))

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

(defn status
  "Return a closed, read-only operational snapshot for an embedded lifecycle.

  The snapshot contains only lifecycle phase and bounded scalar span-pipeline
  counters. Durable freshness remains explicitly unavailable until jolt-chdb
  exposes a public status capability. Ownership-bearing objects, endpoints,
  exceptions, and telemetry values are never returned."
  [lifecycle]
  (if-let [status-fn (:status-fn lifecycle)]
    (status-fn)
    (invalid! "invalid oscope embedded lifecycle" ::invalid-lifecycle)))

(defn status-v2
  "Return the version-2 closed embedded status snapshot.

  `status` remains the exact version-1 compatibility API.  Version 2 adds a
  strict projection of Durable's public persistence observation only while its
  owned JDBC connection is open.  It never derives delivery, remote freshness,
  or a combined queue/persistence-current claim."
  [lifecycle]
  (if-let [status-fn (:status-v2-fn lifecycle)]
    (status-fn)
    (invalid! "invalid oscope embedded lifecycle" ::invalid-lifecycle)))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    (invalid! "invalid oscope embedded lifecycle" ::invalid-lifecycle)))
