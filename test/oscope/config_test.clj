(ns oscope.config-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.server-main :as server-main]))

(def file-document
  "{:version 1 :server {:host \"127.0.0.1\" :port 5000}
    :ingest {:type :otlp-http-json}
    :storage {:type :local-path :path \"/private/file\"}}")

(deftest precedence-and-storage-replacement
  (let [resolved (config/resolve-config
                  [[:file (config/parse file-document)]
                   [:environment {:server {:port 6000}
                                  :storage {:type :memory}}]
                   [:cli {:server {:port 7000}}]])]
    (is (= 7000 (get-in resolved [:config :server :port])))
    (is (= :cli (get-in resolved [:provenance [:server :port]])))
    (is (= :memory (get-in resolved [:config :storage :type])))
    (is (nil? (get-in resolved [:config :storage :path])))
    (is (nil? (get-in resolved [:provenance [:storage :path]])))
    (is (= :environment
           (get-in resolved [:provenance [:storage :type]])))))

(deftest version-one-normalizes-to-version-two-with-typed-attributes-disabled
  (let [legacy (config/parse file-document)
        normalized (config/file-document legacy)
        resolved (config/resolve-config [[:file legacy]])]
    (is (= 2 (:version normalized)))
    (is (= {:mode :disabled} (:typed-attributes normalized)))
    (is (= {:mode :disabled}
           (get-in resolved [:config :typed-attributes])))
    (is (= :file
           (get-in resolved
                   [:provenance [:typed-attributes :mode]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/file-document
                  (assoc legacy :typed-attributes {:mode :disabled}))))))

(deftest typed-attributes-are-a-whole-section-replacement
  (let [selector {:dataset-id "private-dataset"
                  :application-id "private-app"
                  :lineage "private-lineage"
                  :version 7}
        install {:mode :install
                 :manifest {:path "/private/manifest.edn"
                            :sha256 (apply str (repeat 64 "a"))}
                 :registry selector}
        resolved (config/resolve-config
                  [[:file {:typed-attributes install}]
                   [:cli {:typed-attributes {:mode :disabled}}]])]
    (is (= {:mode :disabled}
           (get-in resolved [:config :typed-attributes])))
    (is (= :cli
           (get-in resolved
                   [:provenance [:typed-attributes :mode]])))
    (is (nil? (get-in resolved
                      [:provenance [:typed-attributes :manifest :path]])))
    (is (nil? (get-in resolved
                      [:provenance [:typed-attributes :registry :lineage]])))))

(deftest explicit-file-selection-has-no-cwd-fallback
  (is (nil? (cli/config-path (cli/parse-args []) {})))
  (is (= "/env.edn"
         (cli/config-path (cli/parse-args [])
                          {"OSCOPE_CONFIG" "/env.edn"})))
  (is (= "/cli.edn"
         (cli/config-path (cli/parse-args ["--config" "/cli.edn"])
                          {"OSCOPE_CONFIG" "/env.edn"}))))

(deftest cli-overrides-environment-and-file
  (let [resolved (cli/load-config
                  ["--config" "/chosen" "--host" "127.0.0.1"
                   "--port" "7000" "--db-spec" "chdb::memory:"
                   "--check-config"]
                  {"OSCOPE_CONFIG" "/ignored"
                   "OSCOPE_PORT" "6000"
                   "OSCOPE_CHDB_SPEC" "chdb:/private/environment"}
                  (fn [path]
                    (is (= "/chosen" path))
                    file-document))]
    (is (:check-config? resolved))
    (is (= {:host "127.0.0.1" :port 7000
            :http-workers 2 :http-queue-capacity 8
            :db-spec "chdb::memory:"}
           (cli/server-options resolved)))
    (is (= :cli (get-in resolved [:provenance [:storage :type]])))))

(deftest validation-is-closed-and-side-effect-free
  (doseq [document [(assoc config/defaults :unknown true)
                    (assoc-in config/defaults [:server :unknown] true)
                    (assoc config/defaults :version 3)
                    (assoc config/defaults :storage
                           {:type :local-path :path "/ok" :root "/stale"})]]
    (is (thrown? clojure.lang.ExceptionInfo (config/validate document))))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/file-document {:server {:port 5000}}))))

(deftest typed-attribute-variants-are-closed-and-bounded
  (let [selector {:dataset-id "dataset"
                  :application-id "app"
                  :lineage "lineage-v1"
                  :version 1}
        digest (apply str (repeat 64 "a"))
        document #(assoc config/defaults :typed-attributes %)]
    (doseq [value [{:mode :disabled}
                   {:mode :install
                    :manifest {:path "/private/manifest.edn"
                               :sha256 digest}
                    :registry selector}
                   {:mode :acquire :registry selector}]]
      (is (= value (:typed-attributes (config/validate (document value))))))
    (doseq [value [{:mode :disabled :registry selector}
                   {:mode :install
                    :manifest {:path "relative.edn" :sha256 digest}
                    :registry selector}
                   {:mode :install
                    :manifest {:path "/private/manifest.edn"
                               :sha256 (.toUpperCase digest)}
                    :registry selector}
                   {:mode :acquire
                    :registry (assoc selector :unknown true)}
                   {:mode :acquire
                    :registry (assoc selector :dataset-id "secret value")}
                   {:mode :acquire
                    :registry (assoc selector :version 0)}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/validate (document value)))))))

(deftest storage-variants-and-credential-references
  (doseq [storage [{:type :memory}
                   {:type :local-path :path "/data"}
                   {:type :durable-local :root "/durable"}
                   {:type :durable-s3
                    :s3 {:endpoint "https://s3.example.test"
                         :bucket "telemetry" :region "us-west-2"
                         :object-id "oscope"
                         :credentials {:type :environment
                                       :access-key-env "AWS_ACCESS_KEY_ID"
                                       :secret-key-env "AWS_SECRET_ACCESS_KEY"
                                       :session-token-env "AWS_SESSION_TOKEN"}}}]]
    (is (= storage
           (:storage (config/validate (assoc config/defaults
                                             :storage storage))))))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate
                (assoc config/defaults :storage
                       {:type :durable-s3
                        :s3 {:endpoint "https://s3.example.test"
                             :bucket "telemetry" :region "us-west-2"
                             :object-id "oscope"
                             :credentials {:type :inline
                                           :access-key "PRIVATE"}}})))))

(deftest durable-cross-field-boundaries
  (let [document (fn [storage] (assoc config/defaults :storage storage))]
    (is (= 10 (get-in (config/validate
                       (document {:type :durable-local :root "/durable"
                                  :lease-ttl-ms 30
                                  :heartbeat-interval-ms 10}))
                      [:storage :heartbeat-interval-ms])))
    (doseq [storage [{:type :durable-local :root "/durable"
                      :database "7invalid"}
                     {:type :durable-local :root "/durable"
                      :lease-ttl-ms 30 :heartbeat-interval-ms 11}
                     {:type :durable-local :root "/durable"
                      :retry-initial-backoff-ms 11
                      :retry-max-backoff-ms 10}
                     {:type :durable-s3
                      :s3 {:endpoint "https://s3.example.test"
                           :bucket "telemetry" :region "us-west-2"
                           :object-id "oscope" :max-attempts 9
                           :credentials {:type :environment
                                         :access-key-env "AWS_ACCESS_KEY_ID"
                                         :secret-key-env "AWS_SECRET_ACCESS_KEY"}}}
                     {:type :durable-s3
                      :s3 {:endpoint "https://s3.example.test"
                           :bucket "telemetry" :region "us-west-2"
                           :object-id "oscope"
                           :retry-initial-backoff-ms 11
                           :retry-max-backoff-ms 10
                           :credentials {:type :environment
                                         :access-key-env "AWS_ACCESS_KEY_ID"
                                         :secret-key-env "AWS_SECRET_ACCESS_KEY"}}}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/validate (document storage)))))))

(deftest diagnostics-redact-sensitive-paths
  (let [resolved (config/resolve-config
                  [[:file {:storage {:type :local-path
                                     :path "/private/tenant/data"}}]])
        rendered (pr-str (config/redact resolved))]
    (is (not (.contains rendered "/private/tenant/data")))
    (is (.contains rendered "<redacted>"))))

(deftest durable-diagnostics-redact-operator-identity
  (let [resolved
        (config/resolve-config
         [[:file {:storage {:type :durable-local
                            :root "/private/root"
                            :scratch-parent "/private/scratch"
                            :owner "private-owner"
                            :instance "private-instance"
                            :database "private_database"}}]])
        rendered (pr-str (config/redact resolved))]
    (doseq [private-value ["/private/root" "/private/scratch"
                           "private-owner" "private-instance"
                           "private_database"]]
      (is (not (.contains rendered private-value))))))

(deftest typed-attribute-diagnostics-redact-path-and-selector-strings
  (let [private-values ["/private/manifest.edn" "private-dataset"
                        "private-app" "private-lineage"]
        resolved
        (config/resolve-config
         [[:file
           {:typed-attributes
            {:mode :install
             :manifest {:path (first private-values)
                        :sha256 (apply str (repeat 64 "a"))}
             :registry {:dataset-id (second private-values)
                        :application-id (nth private-values 2)
                        :lineage (nth private-values 3)
                        :version 4}}}]])
        rendered (config/encode-diagnostic resolved)]
    (doseq [private-value private-values]
      (is (not (.contains rendered private-value))))
    (is (.contains rendered "<redacted>"))
    (is (.contains rendered ":install"))))

(deftest tagged-values-are-rejected-on-jolt
  (doseq [text ["{:version 1 :value #uuid \"00000000-0000-0000-0000-000000000000\"}"
                "{:version 1 :value #inst \"2026-09-09T00:00:00Z\"}"]]
    (let [error (try (config/parse text) nil
                     (catch Throwable error error))]
      (is (:oscope.config/error (ex-data error)))
      (is (= "tagged values are not allowed" (ex-message error))))))

(deftest file-read-errors-do-not-echo-sensitive-paths
  (let [error (try
                (cli/load-config ["--config" "/private/tenant/config.edn"]
                                 {}
                                 (fn [_]
                                   (throw (ex-info "leaked /private/tenant/config.edn"
                                                   {:path "/private/tenant/config.edn"}))))
                nil
                (catch Throwable error error))
        rendered (pr-str [(ex-message error) (ex-data error)])]
    (is (:oscope.config/error (ex-data error)))
    (is (not (.contains rendered "/private/tenant")))))

(deftest canonical-edn-roundtrip
  (let [encoded (config/encode config/defaults)]
    (is (= encoded (config/encode (config/parse encoded))))
    (is (= config/defaults (config/parse encoded)))))

(defn- checked-file [storage]
  (cli/load-config
   ["--config" "/private/oscope.edn" "--check-config"]
   {}
   (fn [_]
     (config/encode (assoc config/defaults :storage storage)))))

(deftest durable-check-output-is-safe-parseable-diagnostic-edn
  (doseq [[storage private-values]
          [[{:type :durable-local
             :root "/private/durable-root"
             :scratch-parent "/private/scratch"
             :owner "private-owner"
             :instance "private-instance"
             :database "private_database"}
            ["/private/durable-root" "/private/scratch" "private-owner"
             "private-instance" "private_database"]]
           [{:type :durable-s3
             :scratch-parent "/private/scratch-s3"
             :owner "s3-owner" :instance "s3-instance"
             :database "s3_database"
             :s3 {:endpoint "https://private-s3.example.test"
                  :bucket "private-bucket" :prefix "private/prefix"
                  :region "us-west-2" :object-id "private-object"
                  :credentials {:type :environment
                                :access-key-env "AWS_ACCESS_KEY_ID"
                                :secret-key-env "AWS_SECRET_ACCESS_KEY"}}}
            ["/private/scratch-s3" "s3-owner" "s3-instance" "s3_database"
             "https://private-s3.example.test" "private-bucket"
             "private/prefix" "private-object"]]]]
    (let [resolved (checked-file storage)
          output (cli/check-output resolved)
          displayed (config/parse output)]
      (is (= output (cli/check-output resolved)))
      (is (= (:type storage) (get-in displayed [:storage :type])))
      (is (= "<redacted>" (get-in displayed [:storage :database])))
      (doseq [private-value private-values]
        (is (not (.contains output private-value))))
      ;; Diagnostic placeholders are intentionally not a launchable config.
      (is (thrown? clojure.lang.ExceptionInfo (config/validate displayed)))
      ;; The pre-fix composition revalidated the placeholder and crashed.
      (is (thrown? clojure.lang.ExceptionInfo
                   (config/encode (:config (config/redact resolved))))))))

(deftest launcher-check-path-does-not-start-storage
  (let [resolved (checked-file
                  {:type :durable-local :root "/private/durable"
                   :database "private_database"})
        output (with-out-str
                 (with-redefs [server-main/run!
                               (fn [_]
                                 (throw (ex-info "must not start" {})))]
                   (server-main/execute! resolved)))]
    (is (= (cli/check-output resolved) output))
    (is (not (.contains output "/private/durable")))
    (is (not (.contains output "private_database")))))

(deftest compatibility-environment-aliases
  (is (= {:server {:host "127.0.0.1" :port 14318
                   :http-workers 3 :http-queue-capacity 11}
          :storage {:type :local-path :path "/data"}}
         (config/environment-layer
          {"OSCOPE_HOST" "127.0.0.1"
           "OSCOPE_PORT" "14318"
           "OSCOPE_CHDB_SPEC" "chdb:/data"
           "OSCOPE_HTTP_WORKERS" "3"
           "OSCOPE_HTTP_QUEUE_CAPACITY" "11"}))))
