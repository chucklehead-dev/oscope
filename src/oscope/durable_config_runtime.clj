(ns oscope.durable-config-runtime
  "Materialize one validated Durable configuration after check-only handling."
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [oscope.config-cli :as config-cli])
  (:import [java.io File]))

(def ^:private registry-object-id "typed-attribute-registry-v1")

(defn- fail! [message type]
  ;; Never retain config values, credential names/values, backend exceptions,
  ;; paths, endpoints, or object identities at this public boundary.
  (throw (ex-info message
                  {:oscope.durable-config-runtime/error true :type type})))

(defn- durable-storage! [resolved]
  (let [storage (get-in resolved [:config :storage])]
    (when-not (contains? #{:durable-local :durable-s3} (:type storage))
      (fail! "the Durable launcher requires durable-local or durable-s3 storage"
             ::unsupported-storage))
    storage))

(defn validate-resolved!
  "Require a validated Durable storage variant without performing runtime I/O."
  [resolved]
  (durable-storage! resolved)
  resolved)

(defn- credential-value [credential-source reference]
  (if (ifn? credential-source)
    (credential-source reference)
    (get credential-source reference)))

(defn- secret! [credential-source reference category]
  (or (not-empty (credential-value credential-source reference))
      (fail! "a required Durable S3 credential is unavailable" category)))

(defn- typed-schema [handoff registry-backend]
  (case (:mode handoff)
    :disabled nil
    :install {:mode :install
              :approved-manifest (:approved-manifest handoff)
              :registry-backend registry-backend}
    :acquire {:mode :acquire
              :selector (:registry handoff)
              :registry-backend registry-backend}
    (fail! "the typed attribute runtime handoff is invalid"
           ::invalid-typed-handoff)))

(defn- local-registry-root [root]
  ;; Match the ordinary local-path launcher: canonicalize the configured
  ;; namespace before deriving a sibling registry so aliases cannot fork it.
  (str (.getCanonicalPath (File. root)) ".oscope-registry"))

(defn- common-writer-options [storage instance-fn]
  {:owner (or (:owner storage) "oscope")
   :instance (or (:instance storage) (instance-fn))
   :database (or (:database storage) "default")
   :scratch-parent (or (:scratch-parent storage)
                       (System/getProperty "java.io.tmpdir"))
   :lease-ttl-ms (or (:lease-ttl-ms storage) 30000)
   :heartbeat-interval-ms (:heartbeat-interval-ms storage)
   :clock-skew-ms (or (:clock-skew-ms storage) 0)
   :max-attempts (or (:max-attempts storage) 4)
   :retry-deadline-ms (or (:retry-deadline-ms storage) 5000)
   :retry-initial-backoff-ms (or (:retry-initial-backoff-ms storage) 10)
   :retry-max-backoff-ms (or (:retry-max-backoff-ms storage) 250)
   :force? (boolean (:force? storage))})

(defn server-options
  "Construct Durable server ownership only after config validation/check mode.

  The injected arity keeps backend and credential effects causal in tests."
  ([resolved credential-source]
   (server-options resolved credential-source
                   {:local-backend-fn local-posix/local-backend
                    :s3-backend-fn s3-curl/s3-backend
                    :object-backend-fn backend/object-backend
                    :instance-fn #(str (random-uuid))}))
  ([resolved credential-source
    {:keys [local-backend-fn s3-backend-fn object-backend-fn instance-fn]}]
   (let [storage (durable-storage! resolved)
         handoff (or (config-cli/typed-attributes-handoff resolved)
                     {:mode :disabled})
         common (common-writer-options storage instance-fn)
         {:keys [db-spec registry-backend]}
         (case (:type storage)
           :durable-local
           (let [root (:root storage)
                 telemetry-backend
                 (try (local-backend-fn root)
                      (catch Throwable _
                        (fail! "the Durable local backend could not be opened"
                               ::backend-unavailable)))
                 registry-backend
                 (when-not (= :disabled (:mode handoff))
                   (try (object-backend-fn
                         (local-backend-fn (local-registry-root root))
                         registry-object-id)
                        (catch Throwable _
                          (fail! "the typed attribute registry could not be opened"
                                 ::registry-unavailable))))]
             {:db-spec (durable/writer-dbspec
                        (assoc common :backend telemetry-backend))
              :registry-backend registry-backend})

           :durable-s3
           (let [s3 (:s3 storage)
                 credentials (:credentials s3)
                 s3-options
                 {:endpoint (:endpoint s3)
                  :bucket (:bucket s3)
                  :prefix (or (:prefix s3) "")
                  :region (:region s3)
                  :access-key (secret! credential-source
                                       (:access-key-env credentials)
                                       ::access-key-unavailable)
                  :secret-key (secret! credential-source
                                       (:secret-key-env credentials)
                                       ::secret-key-unavailable)
                  :session-token
                  (when-let [reference (:session-token-env credentials)]
                    (secret! credential-source reference
                             ::session-token-unavailable))
                  :max-attempts (or (:max-attempts s3) 3)
                  :connect-timeout-ms (or (:connect-timeout-ms s3) 10000)
                  :timeout-ms (or (:timeout-ms s3) 300000)
                  :retry-deadline-ms (or (:retry-deadline-ms s3) 300000)
                  :retry-initial-backoff-ms
                  (or (:retry-initial-backoff-ms s3) 25)
                  :retry-max-backoff-ms (or (:retry-max-backoff-ms s3) 1000)}
                 namespace-backend
                 (try (s3-backend-fn s3-options)
                      (catch Throwable _
                        (fail! "the Durable S3 backend could not be opened"
                               ::backend-unavailable)))
                 registry-backend
                 (when-not (= :disabled (:mode handoff))
                   (try (object-backend-fn namespace-backend registry-object-id)
                        (catch Throwable _
                          (fail! "the typed attribute registry could not be opened"
                                 ::registry-unavailable))))]
             {:db-spec (durable/writer-dbspec
                        (assoc common
                               :namespace-backend namespace-backend
                               :object-id (:object-id s3)))
              :registry-backend registry-backend}))
         typed (typed-schema handoff registry-backend)
         document (:config resolved)]
     (cond->
      {:host (get-in document [:server :host])
       :port (get-in document [:server :port])
       :http-workers (get-in document [:server :http-workers])
       :http-queue-capacity (get-in document [:server :http-queue-capacity])
       :durability {:checkpoint! durable/checkpoint!
                    :flush! durable/flush!
                    :checkpoint-every-batches
                    (or (:checkpoint-every-batches storage) 1000)}
       :db-spec db-spec}
       typed (assoc :typed-schema typed)))))
