(ns oscope.durable-server-main
  "Standalone oscope collector backed by chDB Durable V1 local storage."
  (:require [clojure.string :as str]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head]
            [jdbc.chdb.durable.local-posix :as local-posix]
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
    :action "install a compatible qualified chDB build before retrying"}})

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

(defn durable-options
  "Build server options from an environment-shaped map.

  `backend-fn` and `instance-fn` keep parsing deterministic in tests."
  ([environment]
   (durable-options environment local-posix/local-backend #(str (random-uuid))))
  ([environment backend-fn instance-fn]
   (let [root (get environment "OSCOPE_DURABLE_ROOT")
         owner (or (not-empty (get environment "OSCOPE_DURABLE_OWNER"))
                   "oscope")
         instance (or (not-empty (get environment "OSCOPE_DURABLE_INSTANCE"))
                      (instance-fn))
         database (or (not-empty (get environment "OSCOPE_DURABLE_DATABASE"))
                      "default")
         scratch-parent (or (not-empty
                             (get environment "OSCOPE_DURABLE_SCRATCH_PARENT"))
                            (System/getProperty "java.io.tmpdir"))]
     (when (str/blank? root)
       (throw (ex-info "OSCOPE_DURABLE_ROOT is required"
                       {:oscope.durable-server/error true
                        :option "OSCOPE_DURABLE_ROOT"})))
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
       {:host host
        :port port
        :durability {:checkpoint! durable/checkpoint!
                     :flush! durable/flush!}
        :db-spec
        {:vendor "chdb-durable"
         :backend (backend-fn root)
         :owner owner
         :instance instance
         :database database
         :scratch-parent scratch-parent
         :lease-ttl-ms ttl
         :heartbeat-interval-ms heartbeat
         :clock-skew-ms skew
         :force? force?}}))))

(defn env-options []
  (durable-options
   (into {}
         (map (fn [name] [name (System/getenv name)]))
         ["OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_DURABLE_ROOT"
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
