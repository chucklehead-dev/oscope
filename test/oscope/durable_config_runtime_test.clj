(ns oscope.durable-config-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [db.driver :as driver]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable :as durable]
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

(deftest checkpoint-reference-policy-is-validated-and-reaches-the-writer
  (doseq [storage [{:type :durable-local :root "/unused"}
                   (s3-storage)]
          threshold [nil 1 128]]
    (let [storage (cond-> storage threshold (assoc :checkpoint-wal-reference-threshold threshold))
          resolved (resolved storage)
          namespace (backend/memory-backend)
          options (runtime/server-options
                    resolved {"ACCESS_REF" "test-access" "SECRET_REF" "test-secret"}
                    {:local-backend-fn (constantly namespace)
                     :s3-backend-fn (constantly namespace)
                     :instance-fn (constantly "threshold-test")})
          opened (with-redefs [durable/open-writer! identity]
                   (driver/open-handle durable/durable-driver (:db-spec options)))]
      (is (= (:config resolved) (config/parse (config/encode (:config resolved)))))
      (is (= threshold (:checkpoint-wal-reference-threshold opened)))
      (is (= (some? threshold) (contains? opened :checkpoint-wal-reference-threshold)))
      (is (= 1000 (get-in options [:durability :checkpoint-every-batches])))))
  (doseq [value [nil false 0 -1 1.5 "128"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolved {:type :durable-local :root "/unused"
                            :checkpoint-wal-reference-threshold value}))))
  (doseq [storage [{:type :memory} {:type :local-path :path "/unused"}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolved (assoc storage :checkpoint-wal-reference-threshold 128))))))

(deftest check-config-does-not-resolve-credentials-or-create-runtime-owners
  (let [resolved (assoc (resolved (s3-storage)) :check-config? true)
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
