(ns oscope.durable-config-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.s3 :as s3]
            [oscope.config :as config]
            [oscope.durable-config-runtime :as runtime]
            [oscope.durable-server-main :as durable-main]))

(defn- resolved [storage]
  (config/resolve-config [[:file {:version 2 :storage storage}]]))

(defn- s3-storage []
  {:type :durable-s3
   :s3 {:endpoint "https://s3.example.test"
        :bucket "telemetry"
        :region "us-west-2"
        :object-id "oscope"
        :credentials {:type :environment
                      :access-key-env "ACCESS_REF"
                      :secret-key-env "SECRET_REF"}}})

(deftest check-config-does-not-resolve-credentials-or-create-runtime-owners
  (let [resolved (assoc (resolved (assoc (s3-storage)
                                       :owned-compact-stream? true
                                       :export-options
                                       {:insert-format :json-compact-each-row
                                        :json-backend :native-guarded-byte-batch
                                        :owned-statement-output? true
                                        :datetime64-wire :raw-ticks})) :check-config? true)
        effects (atom [])
        output (with-out-str
                 (durable-main/execute!
                  resolved {}
                  (fn [& _] (swap! effects conj :server-options))
                  (fn [& _] (swap! effects conj :run))))]
    (is (empty? @effects))
    (is (re-find #":type :durable-s3" output))
    (is (not (re-find #"s3.example|telemetry|oscope|ACCESS_REF|SECRET_REF"
                      output)))))

(deftest legacy-environment-layer-retains-only-credential-references
  (let [layer
        (durable-main/durable-environment-layer
         {"OSCOPE_DURABLE_BACKEND" "s3"
          "OSCOPE_DURABLE_S3_ENDPOINT" "https://s3.example.test"
          "OSCOPE_DURABLE_S3_BUCKET" "telemetry"
          "OSCOPE_DURABLE_S3_REGION" "us-west-2"
          "OSCOPE_DURABLE_S3_ACCESS_KEY" "ACCESS-CANARY"
          "OSCOPE_DURABLE_S3_SECRET_KEY" "SECRET-CANARY"
          "OSCOPE_DURABLE_S3_SESSION_TOKEN" "SESSION-CANARY"})
        credentials (get-in layer [:storage :s3 :credentials])]
    (is (= {:type :environment
            :access-key-env "OSCOPE_DURABLE_S3_ACCESS_KEY"
            :secret-key-env "OSCOPE_DURABLE_S3_SECRET_KEY"
            :session-token-env "OSCOPE_DURABLE_S3_SESSION_TOKEN"}
           credentials))
    (is (not (re-find #"(?:ACCESS|SECRET|SESSION)-CANARY" (pr-str layer))))))

(deftest precedence-replaces-the-whole-storage-variant
  (let [file-text
        (pr-str {:version 2
                 :storage (s3-storage)})
        environment {"OSCOPE_DURABLE_ROOT" "/environment/durable"}
        result (durable-main/resolve-config
                ["--config" "/private/oscope.edn" "--port" "14318"]
                environment
                (constantly file-text)
                (fn [& _] (throw (ex-info "unexpected manifest read" {}))))]
    (is (= {:type :durable-local :root "/environment/durable"}
           (get-in result [:config :storage])))
    (is (= :environment
           (get-in result [:provenance [:storage :root]])))
    (is (= 14318 (get-in result [:config :server :port])))
    (is (= :cli (get-in result [:provenance [:server :port]])))
    (is (nil? (get-in result [:config :storage :s3])))))

(deftest missing-secret-fails-before-s3-backend-creation
  (let [calls (atom [])
        candidate (resolved (s3-storage))
        error (try
                (runtime/server-options
                 candidate {"ACCESS_REF" "ACCESS-CANARY"}
                 {:local-backend-fn
                  (fn [& _] (swap! calls conj :local))
                  :s3-backend-fn
                  (fn [& _] (swap! calls conj :s3))
                  :object-backend-fn
                  (fn [& _] (swap! calls conj :object))
                  :instance-fn (constantly "instance")})
                nil
                (catch Throwable caught caught))]
    (is (some? error))
    (is (empty? @calls))
    (is (= ::runtime/secret-key-unavailable (:type (ex-data error))))
    (is (not (re-find #"ACCESS|SECRET|CANARY|REF|s3.example|telemetry"
                      (str (ex-message error) " " (pr-str (ex-data error))))))))

(deftest file-selected-credential-names-are-looked-up-lazily
  (let [lookups (atom [])
        backend-options (atom nil)]
    (with-redefs [durable/writer-dbspec identity]
      (runtime/server-options
       (resolved (s3-storage))
       (fn [reference]
         (swap! lookups conj reference)
         (get {"ACCESS_REF" "access" "SECRET_REF" "secret"} reference))
       {:local-backend-fn (fn [& _] ::unexpected)
        :s3-backend-fn (fn [options]
                         (reset! backend-options options)
                         ::namespace)
        :object-backend-fn (fn [& _] ::unexpected)
        :instance-fn (constantly "instance")}))
    (is (= ["ACCESS_REF" "SECRET_REF"] @lookups))
    (is (= "access" (:access-key @backend-options)))
    (is (= "secret" (:secret-key @backend-options)))))

(defn- checksum-environment []
  {"OSCOPE_DURABLE_BACKEND" "s3"
   "OSCOPE_DURABLE_S3_ENDPOINT" "https://s3.example.test"
   "OSCOPE_DURABLE_S3_BUCKET" "telemetry"
   "OSCOPE_DURABLE_S3_REGION" "us-west-2"
   "OSCOPE_DURABLE_S3_ACCESS_KEY" "access"
   "OSCOPE_DURABLE_S3_SECRET_KEY" "secret"})

(deftest legacy-env-options-reuses-the-durable-environment-allowlist
  (let [key "OSCOPE_DURABLE_S3_WAL_UPLOAD_CONFIRMATION"
        environment (with-redefs [durable-main/durable-options identity]
                      (durable-main/env-options))]
    (is (contains? environment key))
    (is (= (into #{"OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_HTTP_WORKERS"
                   "OSCOPE_HTTP_QUEUE_CAPACITY"}
                 durable-main/durable-environment-names)
           (set (keys environment)))))
  ;; A new entry must flow through without editing a second literal list.
  (with-redefs [durable-main/durable-options identity
                durable-main/durable-environment-names ["OSCOPE_TEST_ALLOWLIST_CANARY"]]
    (is (contains? (durable-main/env-options) "OSCOPE_TEST_ALLOWLIST_CANARY"))))

(deftest checksum-setting-reaches-file-and-environment-backend-constructors
  (let [key "OSCOPE_DURABLE_S3_WAL_UPLOAD_CONFIRMATION"]
    (is (some #{key} durable-main/durable-environment-names))
    (doseq [selected? [false true]]
      (let [storage (cond-> (s3-storage)
                      selected? (assoc-in [:s3 :wal-upload-confirmation] :sha256-single-put))
            environment (cond-> (checksum-environment)
                          selected? (assoc key "sha256-single-put"))
            captured (atom [])
            backend! (fn [options] (swap! captured conj options) ::namespace)]
        (with-redefs [durable/writer-dbspec identity]
          ;; File resolution is shared by the standalone/embedded adapter.
          (let [file (durable-main/resolve-config
                      ["--config" "/unused/oscope.edn"] {}
                      (constantly (pr-str {:version 2 :storage storage}))
                      (fn [& _] (throw (ex-info "unexpected manifest read" {}))))]
            (runtime/server-options file {"ACCESS_REF" "access" "SECRET_REF" "secret"}
                                    {:s3-backend-fn backend! :instance-fn (constantly "instance")}))
          (runtime/server-options (durable-main/resolve-config [] environment) environment
                                  {:s3-backend-fn backend! :instance-fn (constantly "instance")})
          (durable-main/durable-options environment (fn [& _] ::unexpected)
                                        backend! (constantly "instance")))
        (is (= 3 (count @captured)))
        (doseq [options @captured]
          (is (= selected? (contains? options :wal-upload-confirmation)))
          (is (= (when selected? :sha256-single-put) (:wal-upload-confirmation options))))))))

(deftest invalid-confirmation-environment-fails-before-backend-creation
  (doseq [value ["readback" "true" "SHA256-SINGLE-PUT" "PRIVATE-CANARY"]]
    (let [environment (assoc (checksum-environment)
                             "OSCOPE_DURABLE_S3_WAL_UPLOAD_CONFIRMATION" value)
          calls (atom [])
          error (try
                  (durable-main/durable-options
                   environment (fn [& _] (swap! calls conj :local))
                   (fn [& _] (swap! calls conj :s3)) (constantly "instance"))
                  nil (catch Throwable e e))]
      (is (some? error))
      (is (empty? @calls))
      (is (not (re-find #"PRIVATE-CANARY" (str (ex-message error) (ex-data error)))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (durable-main/resolve-config [] environment))))))

(deftest actual-backend-capability-selection-is-not-requested-configuration
  ;; Real runtime adapter + real native transport factory, without creating a
  ;; writer or issuing requests. A selected HTTPS capability validates its source
  ;; before PUT; the unselected path returns :unsupported without touching it.
  ;; This tests constructor routing, NOT a successful upload confirmation proof.
  (let [missing (str (System/getProperty "java.io.tmpdir")
                     "/oscope-checksum-probe-" (random-uuid))
        digest "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        credentials {"ACCESS_REF" "access" "SECRET_REF" "secret"}]
    (is (not (.exists (java.io.File. missing))))
    (doseq [selected? [false true]]
      (let [storage (cond-> (s3-storage)
                      selected? (assoc-in [:s3 :wal-upload-confirmation] :sha256-single-put))
            options (runtime/server-options (resolved storage) credentials)
            store (get-in options [:db-spec :namespace-backend])]
        (if selected?
          (let [error (try (backend/put-file-with-checksum-if-absent! store "probe" missing 3 digest)
                           nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= ::s3/invalid-file (:type (ex-data error)))))
          (is (= {:status :unsupported}
                 (backend/put-file-with-checksum-if-absent! store "probe" missing 3 digest))))))
    (let [storage (-> (s3-storage)
                      (assoc-in [:s3 :endpoint] "http://127.0.0.1:1")
                      (assoc-in [:s3 :wal-upload-confirmation] :sha256-single-put))
          store (get-in (runtime/server-options (resolved storage) credentials)
                        [:db-spec :namespace-backend])]
      (is (= {:status :unsupported}
             (backend/put-file-with-checksum-if-absent! store "probe" missing 3 digest))))
    (let [calls (atom 0)
          storage (assoc-in (s3-storage) [:s3 :wal-upload-confirmation] :sha256-single-put)
          options (runtime/server-options
                   (resolved storage) credentials
                   {:instance-fn (constantly "instance")
                    :s3-backend-fn #(s3/s3-backend
                                    (assoc % :request! (fn [& _] (swap! calls inc))))})
          store (get-in options [:db-spec :namespace-backend])]
      (is (= {:status :unsupported}
             (backend/put-file-with-checksum-if-absent! store "probe" missing 3 digest)))
      (is (zero? @calls)))))

(deftest legacy-local-environment-has-the-same-runtime-options
  (let [environment
        {"OSCOPE_DURABLE_ROOT" "/private/durable"
         "OSCOPE_DURABLE_OWNER" "collector"
         "OSCOPE_DURABLE_INSTANCE" "collector-17"
         "OSCOPE_DURABLE_DATABASE" "telemetry"
         "OSCOPE_DURABLE_SCRATCH_PARENT" "/private/scratch"
         "OSCOPE_DURABLE_LEASE_TTL_MS" "60000"
         "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS" "15000"
         "OSCOPE_DURABLE_CLOCK_SKEW_MS" "250"
         "OSCOPE_DURABLE_MAX_ATTEMPTS" "7"
         "OSCOPE_DURABLE_RETRY_DEADLINE_MS" "8000"
         "OSCOPE_DURABLE_RETRY_INITIAL_BACKOFF_MS" "30"
         "OSCOPE_DURABLE_RETRY_MAX_BACKOFF_MS" "400"
         "OSCOPE_DURABLE_FORCE" "true"
         "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES" "17"
         "OSCOPE_HTTP_WORKERS" "3"
         "OSCOPE_HTTP_QUEUE_CAPACITY" "11"
         "OSCOPE_PORT" "14318"}
        backend ::backend]
    (with-redefs [durable/writer-dbspec identity]
      (let [legacy (durable-main/durable-options
                    environment (constantly backend) (constantly "unused"))
            current (runtime/server-options
                     (durable-main/resolve-config [] environment) environment
                     {:local-backend-fn (constantly backend)
                      :s3-backend-fn (fn [& _] ::unexpected)
                      :object-backend-fn (fn [& _] ::unexpected)
                      :instance-fn (constantly "unused")})]
        (is (= legacy current))))))

(deftest typed-local-registry-has-a-distinct-fixed-object-scope
  (let [namespace-roots (atom [])
        object-calls (atom [])
        candidate
        (assoc (resolved {:type :durable-local :root "/private/durable"})
               :typed-attributes-handoff
               {:mode :acquire
                :registry {:dataset-id "dataset" :application-id "app"
                           :lineage "lineage" :version 1}})]
    (with-redefs [durable/writer-dbspec identity]
      (let [result
            (runtime/server-options
             candidate {}
             {:local-backend-fn
              (fn [root]
                (swap! namespace-roots conj root)
                {:root root})
              :s3-backend-fn (fn [& _] ::unexpected)
              :object-backend-fn
              (fn [namespace object-id]
                (swap! object-calls conj [namespace object-id])
                ::registry)
              :instance-fn (constantly "instance")})]
        (is (= ["/private/durable"
                "/private/durable.oscope-registry"]
               @namespace-roots))
        (is (= [[{:root "/private/durable.oscope-registry"}
                 "typed-attribute-registry-v1"]]
               @object-calls))
        (is (= {:root "/private/durable"}
               (get-in result [:db-spec :backend])))
        (is (= ::registry
               (get-in result [:typed-schema :registry-backend])))))))

(deftest typed-s3-registry-is-distinct-from-the-telemetry-object
  (let [object-calls (atom [])
        candidate
        (assoc (resolved (assoc-in (s3-storage) [:s3 :object-id]
                                   "telemetry-object"))
               :typed-attributes-handoff
               {:mode :acquire
                :registry {:dataset-id "dataset" :application-id "app"
                           :lineage "lineage" :version 1}})]
    (with-redefs [durable/writer-dbspec identity]
      (let [result
            (runtime/server-options
             candidate {"ACCESS_REF" "access" "SECRET_REF" "secret"}
             {:local-backend-fn (fn [& _] ::unexpected)
              :s3-backend-fn (constantly ::namespace)
              :object-backend-fn
              (fn [namespace object-id]
                (swap! object-calls conj [namespace object-id])
                ::registry)
              :instance-fn (constantly "instance")})]
        (is (= "telemetry-object" (get-in result [:db-spec :object-id])))
        (is (= [[::namespace "typed-attribute-registry-v1"]]
               @object-calls))
        (is (= ::registry
               (get-in result [:typed-schema :registry-backend])))))))

(deftest invalid-file-fails-before-runtime-materialization
  (let [effects (atom [])]
    (is (thrown?
         Exception
         (let [resolved
               (durable-main/resolve-config
                ["--config" "/private/invalid.edn"] {}
                (constantly "{:version 2 :storage {:type :durable-local}}")
                (fn [& _] (swap! effects conj :manifest)))]
           (durable-main/execute!
            resolved {}
            (fn [& _] (swap! effects conj :runtime))
            (fn [& _] (swap! effects conj :server))))))
    (is (empty? @effects))))
