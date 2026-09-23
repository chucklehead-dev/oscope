(ns oscope.durable-server-main
  "Standalone oscope collector backed by chDB Durable V1 local storage."
  (:require [clojure.string :as str]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.chdb.durable.s3 :as durable-s3]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [oscope.config :as config]
            [oscope.config-cli :as config-cli]
            [oscope.durable-config-runtime :as config-runtime]
            [oscope.server :as server]
            [oscope.server-main :as server-main]))

(def ^:private failure-details
  {:otel.exporter.chdb.schema/migration-failed
   {:category :schema-migration
    :message "the telemetry schema migration could not be applied"
    :action "preserve the store and inspect internal migration diagnostics before retrying"}
   ::control/lease-held
   {:category :lease-held
    :message "another writer still holds the Durable lease"
    :action "wait for lease expiry, or force takeover only after proving the old process is gone"}
   ::control/lease-fenced
   {:category :lease-fenced
    :message "this process no longer owns the Durable lease"
    :action "stop this instance and investigate the active writer before retrying"}
   ::control/timeout
   {:category :storage-timeout
    :message "a Durable control operation exceeded its retry bounds"
    :action "inspect object-store latency and retry policy before restarting"}
   ::control/commit-ambiguous
   {:category :commit-ambiguous
    :message "a Durable write could not be proved committed or uncommitted"
    :action "stop this instance and preserve the store for reconciliation"}
   ::head/corrupt
   {:category :corrupt-head
    :message "the Durable head is corrupt"
    :action "preserve the store and restore or repair head.json before retrying"}
   ::durable/engine-incompatible
   {:category :engine-incompatible
    :message "the installed chDB engine cannot recover this Durable object"
    :action "install a compatible qualified chDB build before retrying"}
   ::durable-s3/authentication
   {:category :storage-authentication
    :message "the Durable object store rejected its credentials"
    :action "repair the configured object-store credentials before retrying"}
   ::durable-s3/permission
   {:category :storage-permission
    :message "the Durable object store denied the required operation"
    :action "grant object read and conditional write access to the configured prefix"}
   ::durable-s3/throttled
   {:category :storage-throttled
    :message "the Durable object store remained throttled"
    :action "inspect provider limits and retry after the throttle clears"}
   ::durable-s3/transport
   {:category :storage-transport
    :message "the Durable object store could not be reached reliably"
    :action "inspect endpoint, TLS, DNS, and network health before retrying"}
   ::durable-s3/timeout
   {:category :storage-timeout
    :message "the Durable object-store request exceeded its retry deadline"
    :action "inspect provider latency and object-store timeout settings"}
   ::durable-s3/provider
   {:category :storage-provider
    :message "the Durable object store rejected a protocol operation"
    :action "inspect the provider status and S3 conditional-write compatibility"}
   ::durable-s3/invalid-response
   {:category :storage-response
    :message "the Durable object store returned an invalid response"
    :action "preserve the store and inspect provider compatibility before retrying"}
   ::durable-s3/invalid-options
   {:category :storage-configuration
    :message "the Durable object-store configuration is invalid"
    :action "repair the named object-store setting before retrying"}
   ::durable-s3/invalid-key
   {:category :storage-configuration
    :message "the Durable object-store namespace is invalid"
    :action "repair the configured object prefix before retrying"}})

(defn- causal-known-failure [error]
  (loop [current error remaining 8]
    (when (and current (pos? remaining))
      (let [type (:type (ex-data current))]
        ;; chDB's public startup envelope intentionally replaces the outer
        ;; type. Keep a known, closed diagnostic from its in-process cause;
        ;; never render the cause's message or data. Unknown types cannot mask
        ;; a known category farther down the bounded chain.
        (or (when (keyword? type) (get failure-details type))
            (recur (ex-cause current) (dec remaining)))))))

(def ^:private durable-startup-stages
  "The closed public stage vocabulary introduced by jolt-chdb Durable open.

  Keep this list here rather than rendering arbitrary values from exception
  data: an operator-facing failure line must not become a route for store
  paths, credentials, backend text, or application payloads."
  #{:capability :read-head :acquire-lease :create-scratch
    :open-native :recover :renew-lease :start-writer})

(defn- causal-startup-stage
  "Return only a recognised Durable-open stage from a bounded cause chain.

  Older jolt-chdb versions simply have no such envelope, so callers retain the
  existing generic diagnostic. Do not derive a stage from a cause type or
  arbitrary ex-data: the Durable envelope is the sole contract."
  [error]
  (loop [current error remaining 8]
    (when (and current (pos? remaining))
      (let [data (ex-data current)
            stage (:jdbc.chdb.durable/startup-stage data)]
        (if (and (= ::durable/startup-failed (:type data))
                 (keyword? stage)
                 (contains? durable-startup-stages stage))
          stage
          (recur (ex-cause current) (dec remaining)))))))

(defn failure-diagnostic
  "Return a bounded operator diagnostic without echoing exception data.

  Driver adapters may wrap the control failure in a SQLException, so the
  classifier follows a bounded cause chain to the first known category; a
  public Durable startup envelope does not mask a known lease or store cause.
  Paths, owner IDs, instance IDs, backend responses, and arbitrary exception
  messages are never returned."
  [error]
  (let [diagnostic
        (or (causal-known-failure error)
            {:category :server-failed
             :message "the Durable server could not start or remain running"
             :action "inspect the structured exception type and retained local logs"})]
    ;; This is deliberately optional. It is additional closed context for the
    ;; current Durable dependency, never a replacement for the generic
    ;; diagnostic used with older or non-Durable startup failures.
    (if-let [stage (causal-startup-stage error)]
      (assoc diagnostic :stage stage)
      diagnostic)))

(defn- report-failure! [error]
  (let [{:keys [category message action stage]} (failure-diagnostic error)]
    (binding [*out* *err*]
      (println (str "oscope Durable server failed [" (name category) "]: "
                    message))
      (when stage
        (println (str "Durable startup stage: " (name stage))))
      (println (str "operator action: " action)))
    (cond-> {:category category :message message}
      stage (assoc :stage stage))))

(defn- positive-long [raw name default]
  (if (str/blank? raw)
    default
    (let [value (parse-long raw)]
      (when-not (and (integer? value) (pos? value))
        (throw (ex-info (str name " must be a positive integer")
                        {:oscope.durable-server/error true :option name})))
      value)))

(defn- attempt-count [raw]
  (let [value (positive-long raw "OSCOPE_DURABLE_S3_MAX_ATTEMPTS" 3)]
    (when (> value 8)
      (throw (ex-info "OSCOPE_DURABLE_S3_MAX_ATTEMPTS must not exceed eight"
                      {:oscope.durable-server/error true
                       :option "OSCOPE_DURABLE_S3_MAX_ATTEMPTS"})))
    value))

(defn- boolean-option [raw name]
  (case (some-> raw str/lower-case)
    nil false
    "" false
    "false" false
    "0" false
    "no" false
    "true" true
    "1" true
    "yes" true
    (throw (ex-info (str name " must be true or false")
                    {:oscope.durable-server/error true :option name}))))

(defn- required-option [environment name]
  (or (not-empty (get environment name))
      (throw (ex-info (str name " is required")
                      {:oscope.durable-server/error true :option name}))))

(defn- object-id! [value]
  (when-not (and (string? value)
                 (not (str/blank? value))
                 (not (contains? #{"." ".."} value))
                 (not (str/includes? value "/"))
                 (not (str/includes? value "\\"))
                 (<= (alength (.getBytes value "UTF-8")) 255))
    (throw (ex-info "OSCOPE_DURABLE_OBJECT_ID is invalid"
                    {:oscope.durable-server/error true
                     :option "OSCOPE_DURABLE_OBJECT_ID"})))
  value)

(def durable-environment-names
  ["OSCOPE_DURABLE_ROOT" "OSCOPE_DURABLE_BACKEND"
   "OSCOPE_DURABLE_OBJECT_ID" "OSCOPE_DURABLE_S3_ENDPOINT"
   "OSCOPE_DURABLE_S3_BUCKET" "OSCOPE_DURABLE_S3_PREFIX"
   "OSCOPE_DURABLE_S3_REGION" "OSCOPE_DURABLE_S3_ACCESS_KEY"
   "OSCOPE_DURABLE_S3_SECRET_KEY" "OSCOPE_DURABLE_S3_SESSION_TOKEN"
   "OSCOPE_DURABLE_S3_MAX_ATTEMPTS"
   "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS" "OSCOPE_DURABLE_S3_TIMEOUT_MS"
   "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS"
   "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS"
   "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS" "OSCOPE_DURABLE_OWNER"
   "OSCOPE_DURABLE_INSTANCE" "OSCOPE_DURABLE_DATABASE"
   "OSCOPE_DURABLE_SCRATCH_PARENT" "OSCOPE_DURABLE_LEASE_TTL_MS"
   "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS" "OSCOPE_DURABLE_CLOCK_SKEW_MS"
   "OSCOPE_DURABLE_FORCE" "OSCOPE_DURABLE_MAX_ATTEMPTS"
   "OSCOPE_DURABLE_RETRY_DEADLINE_MS"
   "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS"
   "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS"
   "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES"])

(defn- optional-string [environment name]
  (not-empty (get environment name)))

(defn- optional-positive [environment name]
  (when-let [raw (optional-string environment name)]
    (positive-long raw name nil)))

(defn- common-environment-storage [environment]
  (cond-> {}
    (optional-string environment "OSCOPE_DURABLE_OWNER")
    (assoc :owner (get environment "OSCOPE_DURABLE_OWNER"))
    (optional-string environment "OSCOPE_DURABLE_INSTANCE")
    (assoc :instance (get environment "OSCOPE_DURABLE_INSTANCE"))
    (optional-string environment "OSCOPE_DURABLE_DATABASE")
    (assoc :database (get environment "OSCOPE_DURABLE_DATABASE"))
    (optional-string environment "OSCOPE_DURABLE_SCRATCH_PARENT")
    (assoc :scratch-parent (get environment "OSCOPE_DURABLE_SCRATCH_PARENT"))
    (optional-positive environment "OSCOPE_DURABLE_LEASE_TTL_MS")
    (assoc :lease-ttl-ms
           (optional-positive environment "OSCOPE_DURABLE_LEASE_TTL_MS"))
    (optional-positive environment "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS")
    (assoc :heartbeat-interval-ms
           (optional-positive environment
                              "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS"))
    (optional-string environment "OSCOPE_DURABLE_CLOCK_SKEW_MS")
    (assoc :clock-skew-ms
           (let [value (parse-long
                        (get environment "OSCOPE_DURABLE_CLOCK_SKEW_MS"))]
             (when-not (and (integer? value) (not (neg? value)))
               (throw (ex-info "OSCOPE_DURABLE_CLOCK_SKEW_MS must be nonnegative"
                               {:oscope.durable-server/error true
                                :option "OSCOPE_DURABLE_CLOCK_SKEW_MS"})))
             value))
    (optional-string environment "OSCOPE_DURABLE_FORCE")
    (assoc :force? (boolean-option
                    (get environment "OSCOPE_DURABLE_FORCE")
                    "OSCOPE_DURABLE_FORCE"))
    (optional-positive environment "OSCOPE_DURABLE_MAX_ATTEMPTS")
    (assoc :max-attempts
           (optional-positive environment "OSCOPE_DURABLE_MAX_ATTEMPTS"))
    (optional-positive environment "OSCOPE_DURABLE_RETRY_DEADLINE_MS")
    (assoc :retry-deadline-ms
           (optional-positive environment "OSCOPE_DURABLE_RETRY_DEADLINE_MS"))
    (optional-positive environment "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS")
    (assoc :retry-initial-backoff-ms
           (optional-positive
            environment "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS"))
    (optional-positive environment "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS")
    (assoc :retry-max-backoff-ms
           (optional-positive environment
                              "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS"))
    (optional-positive environment "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES")
    (assoc :checkpoint-every-batches
           (optional-positive
            environment "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES"))))

(defn durable-environment-storage
  "Translate a complete legacy Durable environment selection into config data.

  Credential values are deliberately never read into the returned map. The S3
  variant carries only the names of the environment variables that the runtime
  adapter may resolve after check-only handling."
  [environment]
  (let [raw-backend (optional-string environment "OSCOPE_DURABLE_BACKEND")
        root (optional-string environment "OSCOPE_DURABLE_ROOT")]
    (when (or raw-backend root)
      (let [backend-kind (or (some-> raw-backend str/lower-case) "local")
            common (common-environment-storage environment)]
        (case backend-kind
          "local"
          (assoc common :type :durable-local
                 :root (required-option environment "OSCOPE_DURABLE_ROOT"))

          "s3"
          (let [optional-s3
                (cond-> {}
                  (some? (get environment "OSCOPE_DURABLE_S3_PREFIX"))
                  (assoc :prefix (get environment "OSCOPE_DURABLE_S3_PREFIX"))
                  (optional-positive environment "OSCOPE_DURABLE_S3_MAX_ATTEMPTS")
                  (assoc :max-attempts
                         (attempt-count
                          (get environment "OSCOPE_DURABLE_S3_MAX_ATTEMPTS")))
                  (optional-positive environment
                                     "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS")
                  (assoc :connect-timeout-ms
                         (optional-positive
                          environment "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS"))
                  (optional-positive environment "OSCOPE_DURABLE_S3_TIMEOUT_MS")
                  (assoc :timeout-ms
                         (optional-positive environment
                                            "OSCOPE_DURABLE_S3_TIMEOUT_MS"))
                  (optional-positive environment
                                     "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS")
                  (assoc :retry-deadline-ms
                         (optional-positive
                          environment "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS"))
                  (optional-positive
                   environment "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS")
                  (assoc :retry-initial-backoff-ms
                         (optional-positive
                          environment
                          "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS"))
                  (optional-positive
                   environment "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS")
                  (assoc :retry-max-backoff-ms
                         (optional-positive
                          environment
                          "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS")))]
            (assoc common :type :durable-s3
                   :s3
                   (merge
                    {:endpoint (required-option
                                environment "OSCOPE_DURABLE_S3_ENDPOINT")
                     :bucket (required-option
                              environment "OSCOPE_DURABLE_S3_BUCKET")
                     :region (required-option
                              environment "OSCOPE_DURABLE_S3_REGION")
                     :object-id (object-id!
                                 (or (optional-string
                                      environment "OSCOPE_DURABLE_OBJECT_ID")
                                     "oscope"))
                     :credentials
                     (cond->
                      {:type :environment
                       :access-key-env "OSCOPE_DURABLE_S3_ACCESS_KEY"
                       :secret-key-env "OSCOPE_DURABLE_S3_SECRET_KEY"}
                       (optional-string
                        environment "OSCOPE_DURABLE_S3_SESSION_TOKEN")
                       (assoc :session-token-env
                              "OSCOPE_DURABLE_S3_SESSION_TOKEN"))}
                    optional-s3)))

          (throw (ex-info "OSCOPE_DURABLE_BACKEND must be local or s3"
                          {:oscope.durable-server/error true
                           :option "OSCOPE_DURABLE_BACKEND"})))))))

(defn durable-environment-layer [environment]
  (let [storage (durable-environment-storage environment)]
    (cond-> (config/environment-layer environment)
      storage (assoc :storage storage))))

(defn durable-options
  "Build server options from an environment-shaped map.

  `backend-fn` and `instance-fn` keep parsing deterministic in tests."
  ([environment]
   (durable-options environment local-posix/local-backend
                    s3-curl/s3-backend #(str (random-uuid))))
  ([environment backend-fn instance-fn]
   (durable-options environment backend-fn s3-curl/s3-backend instance-fn))
  ([environment local-backend-fn s3-backend-fn instance-fn]
   (let [backend-kind (or (not-empty
                           (some-> (get environment "OSCOPE_DURABLE_BACKEND")
                                   str/lower-case))
                          "local")
         owner (or (not-empty (get environment "OSCOPE_DURABLE_OWNER"))
                   "oscope")
         instance (or (not-empty (get environment "OSCOPE_DURABLE_INSTANCE"))
                      (instance-fn))
         database (or (not-empty (get environment "OSCOPE_DURABLE_DATABASE"))
                      "default")
         scratch-parent (or (not-empty
                             (get environment "OSCOPE_DURABLE_SCRATCH_PARENT"))
                            (System/getProperty "java.io.tmpdir"))]
     (when-not (contains? #{"local" "s3"} backend-kind)
       (throw (ex-info "OSCOPE_DURABLE_BACKEND must be local or s3"
                       {:oscope.durable-server/error true
                        :option "OSCOPE_DURABLE_BACKEND"})))
     (let [http-options (server/http-executor-env-options environment)
           host (or (not-empty (get environment "OSCOPE_HOST"))
                    server/default-host)
           raw-port (get environment "OSCOPE_PORT")
           port (if (str/blank? raw-port)
                  server/default-port
                  (parse-long raw-port))
           ttl (positive-long (get environment "OSCOPE_DURABLE_LEASE_TTL_MS")
                              "OSCOPE_DURABLE_LEASE_TTL_MS" 30000)
           heartbeat
           (when-let [raw (not-empty
                           (get environment
                            "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS"))]
             (positive-long raw "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS" nil))
           skew
           (let [raw (get environment "OSCOPE_DURABLE_CLOCK_SKEW_MS")]
             (if (str/blank? raw)
               0
               (let [value (parse-long raw)]
                 (when-not (and (integer? value) (not (neg? value)))
                   (throw (ex-info
                           "OSCOPE_DURABLE_CLOCK_SKEW_MS must be nonnegative"
                           {:oscope.durable-server/error true
                            :option "OSCOPE_DURABLE_CLOCK_SKEW_MS"})))
                 value)))
           force? (boolean-option (get environment "OSCOPE_DURABLE_FORCE")
                                  "OSCOPE_DURABLE_FORCE")
           max-attempts
           (positive-long (get environment "OSCOPE_DURABLE_MAX_ATTEMPTS")
                          "OSCOPE_DURABLE_MAX_ATTEMPTS" 4)
           retry-deadline-ms
           (positive-long (get environment "OSCOPE_DURABLE_RETRY_DEADLINE_MS")
                          "OSCOPE_DURABLE_RETRY_DEADLINE_MS" 5000)
           retry-initial-backoff-ms
           (positive-long
            (get environment "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS")
            "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS" 10)
           retry-max-backoff-ms
           (positive-long
            (get environment "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS")
            "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS" 250)
           checkpoint-every
           (positive-long
            (get environment "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES")
            "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES" 1000)]
       (when-not (= server/default-host host)
         (throw (ex-info "OSCOPE_HOST must be 127.0.0.1"
                         {:oscope.durable-server/error true
                          :option "OSCOPE_HOST"})))
       (when-not (and (integer? port) (<= 0 port 65535))
         (throw (ex-info "OSCOPE_PORT must be between 0 and 65535"
                         {:oscope.durable-server/error true
                          :option "OSCOPE_PORT"})))
       (doseq [[name value] [["OSCOPE_DURABLE_OWNER" owner]
                             ["OSCOPE_DURABLE_INSTANCE" instance]]]
         (when (str/blank? value)
           (throw (ex-info (str name " must be nonblank")
                           {:oscope.durable-server/error true :option name}))))
       (when-not (and (string? database)
                      (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]{0,254}"
                                           database)))
         (throw (ex-info "OSCOPE_DURABLE_DATABASE is invalid"
                         {:oscope.durable-server/error true
                          :option "OSCOPE_DURABLE_DATABASE"})))
       (when (and heartbeat (> heartbeat (quot ttl 3)))
         (throw (ex-info
                 "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS exceeds one third of the lease TTL"
                 {:oscope.durable-server/error true
                  :option "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS"})))
       (when (> retry-initial-backoff-ms retry-max-backoff-ms)
         (throw (ex-info
                 "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS exceeds its maximum"
                 {:oscope.durable-server/error true
                  :option "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS"})))
       (let [storage
             (case backend-kind
               "local"
               {:backend
                (local-backend-fn
                 (required-option environment "OSCOPE_DURABLE_ROOT"))}

               "s3"
               (let [object-id
                     (object-id!
                      (or (not-empty
                          (get environment "OSCOPE_DURABLE_OBJECT_ID"))
                          "oscope"))
                     s3-retry-initial-backoff-ms
                     (positive-long
                      (get environment
                           "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS")
                      "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS" 25)
                     s3-retry-max-backoff-ms
                     (positive-long
                      (get environment
                           "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS")
                      "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS" 1000)
                     _ (when (> s3-retry-initial-backoff-ms
                                s3-retry-max-backoff-ms)
                         (throw
                          (ex-info
                           "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS exceeds its maximum"
                           {:oscope.durable-server/error true
                            :option
                            "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS"})))
                     options
                     {:endpoint (required-option
                                 environment "OSCOPE_DURABLE_S3_ENDPOINT")
                      :bucket (required-option
                               environment "OSCOPE_DURABLE_S3_BUCKET")
                      :prefix (or (get environment "OSCOPE_DURABLE_S3_PREFIX") "")
                      :region (required-option
                               environment "OSCOPE_DURABLE_S3_REGION")
                      :access-key (required-option
                                   environment "OSCOPE_DURABLE_S3_ACCESS_KEY")
                      :secret-key (required-option
                                   environment "OSCOPE_DURABLE_S3_SECRET_KEY")
                      :session-token
                      (not-empty
                       (get environment "OSCOPE_DURABLE_S3_SESSION_TOKEN"))
                      :max-attempts
                      (attempt-count
                       (get environment "OSCOPE_DURABLE_S3_MAX_ATTEMPTS"))
                      :connect-timeout-ms
                      (positive-long
                       (get environment
                            "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS")
                       "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS" 10000)
                      :timeout-ms
                      (positive-long
                       (get environment "OSCOPE_DURABLE_S3_TIMEOUT_MS")
                       "OSCOPE_DURABLE_S3_TIMEOUT_MS" 300000)
                      :retry-deadline-ms
                      (positive-long
                       (get environment
                            "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS")
                       "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS" 300000)
                      :retry-initial-backoff-ms
                      s3-retry-initial-backoff-ms
                      :retry-max-backoff-ms s3-retry-max-backoff-ms}]
                 {:namespace-backend (s3-backend-fn options)
                  :object-id object-id}))]
         {:host host
          :port port
          :http-workers (:http-workers http-options)
          :http-queue-capacity (:http-queue-capacity http-options)
          :durability {:checkpoint! durable/checkpoint!
                       :flush! durable/flush!
                       :checkpoint-every-batches checkpoint-every}
          :db-spec
          (durable/writer-dbspec
           (merge
            {:owner owner
             :instance instance
             :database database
             :scratch-parent scratch-parent
             :lease-ttl-ms ttl
             :heartbeat-interval-ms heartbeat
             :clock-skew-ms skew
             :max-attempts max-attempts
             :retry-deadline-ms retry-deadline-ms
             :retry-initial-backoff-ms retry-initial-backoff-ms
             :retry-max-backoff-ms retry-max-backoff-ms
             :force? force?}
            storage))})))))

(defn env-options []
  (durable-options
   (into {}
         (map (fn [name] [name (System/getenv name)]))
         ["OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_HTTP_WORKERS"
          "OSCOPE_HTTP_QUEUE_CAPACITY" "OSCOPE_DURABLE_ROOT"
          "OSCOPE_DURABLE_BACKEND" "OSCOPE_DURABLE_OBJECT_ID"
          "OSCOPE_DURABLE_S3_ENDPOINT" "OSCOPE_DURABLE_S3_BUCKET"
          "OSCOPE_DURABLE_S3_PREFIX" "OSCOPE_DURABLE_S3_REGION"
          "OSCOPE_DURABLE_S3_ACCESS_KEY" "OSCOPE_DURABLE_S3_SECRET_KEY"
          "OSCOPE_DURABLE_S3_SESSION_TOKEN"
          "OSCOPE_DURABLE_S3_MAX_ATTEMPTS"
          "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS"
          "OSCOPE_DURABLE_S3_TIMEOUT_MS"
          "OSCOPE_DURABLE_S3_RETRY_DEADLINE_MS"
          "OSCOPE_DURABLE_S3_RETRY_INITIAL_BACKOFF_MS"
          "OSCOPE_DURABLE_S3_RETRY_MAX_BACKOFF_MS"
          "OSCOPE_DURABLE_OWNER" "OSCOPE_DURABLE_INSTANCE"
          "OSCOPE_DURABLE_DATABASE" "OSCOPE_DURABLE_SCRATCH_PARENT"
          "OSCOPE_DURABLE_LEASE_TTL_MS"
          "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS"
          "OSCOPE_DURABLE_CLOCK_SKEW_MS" "OSCOPE_DURABLE_FORCE"
          "OSCOPE_DURABLE_MAX_ATTEMPTS"
          "OSCOPE_DURABLE_RETRY_DEADLINE_MS"
          "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS"
          "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS"
          "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES"])))

(defn system-environment []
  (into {}
        (map (fn [name] [name (System/getenv name)]))
        (distinct (concat config-cli/environment-names
                          durable-environment-names))))

(defn resolve-config
  "Resolve the shared CLI/file model plus legacy Durable environment aliases."
  ([arguments environment]
   (config-cli/load-config-with-environment-layer
    arguments environment (durable-environment-layer environment)))
  ([arguments environment read-text read-manifest-bytes]
   (config-cli/load-config-with-environment-layer
    arguments environment (durable-environment-layer environment)
    read-text read-manifest-bytes)))

(defn execute!
  "Check a Durable config without effects, or materialize and run its owner."
  ([resolved environment]
   (execute! resolved environment
             (fn [candidate _]
               ;; Resolve only references named by the already validated
               ;; configuration, and only after check-only handling.
               (config-runtime/server-options candidate #(System/getenv %)))
             server-main/run!))
  ([resolved environment server-options-fn run-fn]
   (config-runtime/validate-resolved! resolved)
   (if (:check-config? resolved)
     (print (config-cli/check-output resolved))
     (run-fn (server-options-fn resolved environment)))))

(defn -main [& arguments]
  (try
    (let [environment (system-environment)]
      (execute! (resolve-config arguments environment) environment))
    (catch Throwable error
      (let [{:keys [category message stage]} (report-failure! error)]
        ;; Do not attach the original cause: an uncaught-exception printer may
        ;; render its message or ex-data after the bounded diagnostic.
        (throw (ex-info message
                        (cond-> {:oscope.durable-server/error true
                                 :category category}
                          stage (assoc :stage stage))))))))
