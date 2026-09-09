(ns oscope.durable-server-main
  "Standalone oscope collector backed by chDB Durable V1 local storage."
  (:require [clojure.string :as str]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.chdb.durable.s3 :as durable-s3]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [oscope.server :as server]
            [oscope.server-main :as server-main]))

(def ^:private failure-details
  {::control/lease-held
   {:category :lease-held
    :message "another writer still holds the Durable lease"
    :action "wait for lease expiry, or force takeover only after proving the old process is gone"}
   ::control/lease-fenced
   {:category :lease-fenced
    :message "this process no longer owns the Durable lease"
    :action "stop this instance and investigate the active writer before retrying"}
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

(defn- causal-type [error]
  (loop [current error remaining 8]
    (when (and current (pos? remaining))
      (or (:type (ex-data current))
          (recur (ex-cause current) (dec remaining))))))

(defn failure-diagnostic
  "Return a bounded operator diagnostic without echoing exception data.

  Driver adapters may wrap the control failure in a SQLException, so the
  classifier follows a bounded cause chain. Paths, owner IDs, instance IDs,
  backend responses, and arbitrary exception messages are never returned."
  [error]
  (or (get failure-details (causal-type error))
      {:category :server-failed
       :message "the Durable server could not start or remain running"
       :action "inspect the structured exception type and retained local logs"}))

(defn- report-failure! [error]
  (let [{:keys [category message action]} (failure-diagnostic error)]
    (binding [*out* *err*]
      (println (str "oscope Durable server failed [" (name category) "]: "
                    message))
      (println (str "operator action: " action)))
    {:category category :message message}))

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
     (let [host (or (not-empty (get environment "OSCOPE_HOST"))
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
                                  "OSCOPE_DURABLE_FORCE")]
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
                       "OSCOPE_DURABLE_S3_TIMEOUT_MS" 300000)}]
                 {:namespace-backend (s3-backend-fn options)
                  :object-id object-id}))]
         {:host host
          :port port
          :durability {:checkpoint! durable/checkpoint!
                       :flush! durable/flush!}
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
             :force? force?}
            storage))})))))

(defn env-options []
  (durable-options
   (into {}
         (map (fn [name] [name (System/getenv name)]))
         ["OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_DURABLE_ROOT"
          "OSCOPE_DURABLE_BACKEND" "OSCOPE_DURABLE_OBJECT_ID"
          "OSCOPE_DURABLE_S3_ENDPOINT" "OSCOPE_DURABLE_S3_BUCKET"
          "OSCOPE_DURABLE_S3_PREFIX" "OSCOPE_DURABLE_S3_REGION"
          "OSCOPE_DURABLE_S3_ACCESS_KEY" "OSCOPE_DURABLE_S3_SECRET_KEY"
          "OSCOPE_DURABLE_S3_SESSION_TOKEN"
          "OSCOPE_DURABLE_S3_MAX_ATTEMPTS"
          "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS"
          "OSCOPE_DURABLE_S3_TIMEOUT_MS"
          "OSCOPE_DURABLE_OWNER" "OSCOPE_DURABLE_INSTANCE"
          "OSCOPE_DURABLE_DATABASE" "OSCOPE_DURABLE_SCRATCH_PARENT"
          "OSCOPE_DURABLE_LEASE_TTL_MS"
          "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS"
          "OSCOPE_DURABLE_CLOCK_SKEW_MS" "OSCOPE_DURABLE_FORCE"])))

(defn -main [& _]
  (try
    (server-main/run! (env-options))
    (catch Throwable error
      (let [{:keys [category message]} (report-failure! error)]
        ;; Do not attach the original cause: an uncaught-exception printer may
        ;; render its message or ex-data after the bounded diagnostic.
        (throw (ex-info message
                        {:oscope.durable-server/error true
                         :category category}))))))
