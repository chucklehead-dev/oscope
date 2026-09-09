(ns oscope.durable-server-main-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head]
            [jdbc.chdb.durable.s3 :as durable-s3]
            [oscope.durable-server-main :as durable-main]
            [oscope.server-main :as server-main]))

(def ^:private test-local-backend (backend/memory-backend))
(def ^:private test-s3-backend (backend/memory-backend))

(defn- wrapped-error [type]
  (ex-info "outer path=/private/store owner=secret instance=secret"
           {:private "must-not-escape"}
           (ex-info "inner secret" {:type type :path ["secret"]})))

(defn- options [environment calls]
  (durable-main/durable-options
   environment
   (fn [root] (swap! calls conj root) test-local-backend)
   (constantly "generated-instance")))

(defn- s3-options [environment calls]
  (durable-main/durable-options
   environment
   (fn [root] (swap! calls conj [:local root]) test-local-backend)
   (fn [options] (swap! calls conj [:s3 options]) test-s3-backend)
   (constantly "generated-instance")))

(deftest durable-environment-builds-a-real-backend-dbspec
  (let [calls (atom [])
        result (options
                {"OSCOPE_DURABLE_ROOT" "/private/durable"
                 "OSCOPE_DURABLE_OWNER" "collector"
                 "OSCOPE_DURABLE_INSTANCE" "collector-17"
                 "OSCOPE_DURABLE_DATABASE" "telemetry"
                 "OSCOPE_DURABLE_SCRATCH_PARENT" "/private/scratch"
                 "OSCOPE_DURABLE_LEASE_TTL_MS" "60000"
                 "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS" "15000"
                 "OSCOPE_DURABLE_CLOCK_SKEW_MS" "250"
                 "OSCOPE_DURABLE_FORCE" "true"
                 "OSCOPE_PORT" "14318"}
                calls)]
    (is (= ["/private/durable"] @calls))
    (is (= {:host "127.0.0.1" :port 14318}
           (select-keys result [:host :port])))
    (is (ifn? (get-in result [:durability :checkpoint!])))
    (is (ifn? (get-in result [:durability :flush!])))
    (is (= {:vendor "chdb-durable"
            :backend test-local-backend
            :owner "collector"
            :instance "collector-17"
            :database "telemetry"
            :scratch-parent "/private/scratch"
            :lease-ttl-ms 60000
            :heartbeat-interval-ms 15000
            :clock-skew-ms 250
            :force? true}
           (:db-spec result)))))

(deftest final-writer-shape-is-validated-by-jolt-chdb
  (let [seen (atom nil)
        calls (atom [])]
    (with-redefs [durable/writer-dbspec
                  (fn [candidate]
                    (reset! seen candidate)
                    ::validated-dbspec)]
      (let [result (options {"OSCOPE_DURABLE_ROOT" "/durable"} calls)]
        (is (= ::validated-dbspec (:db-spec result)))
        (is (= {:backend test-local-backend
                :owner "oscope"
                :instance "generated-instance"
                :database "default"
                :scratch-parent "/tmp"
                :lease-ttl-ms 30000
                :heartbeat-interval-ms nil
                :clock-skew-ms 0
                :force? false}
               @seen))))))

(deftest durable-environment-defaults-identity-and-lease-policy
  (let [calls (atom [])
        result (options {"OSCOPE_DURABLE_ROOT" "/durable"} calls)]
    (is (= "oscope" (get-in result [:db-spec :owner])))
    (is (= "generated-instance" (get-in result [:db-spec :instance])))
    (is (= "default" (get-in result [:db-spec :database])))
    (is (= 30000 (get-in result [:db-spec :lease-ttl-ms])))
    (is (nil? (get-in result [:db-spec :heartbeat-interval-ms])))
    (is (false? (get-in result [:db-spec :force?])))))

(deftest durable-s3-environment-builds-a-namespaced-backend-dbspec
  (let [calls (atom [])
        environment
        {"OSCOPE_DURABLE_BACKEND" "S3"
         "OSCOPE_DURABLE_OBJECT_ID" "telemetry-prod"
         "OSCOPE_DURABLE_S3_ENDPOINT" "https://s3.example.test"
         "OSCOPE_DURABLE_S3_BUCKET" "observability"
         "OSCOPE_DURABLE_S3_PREFIX" "tenant/blue"
         "OSCOPE_DURABLE_S3_REGION" "us-west-2"
         "OSCOPE_DURABLE_S3_ACCESS_KEY" "PRIVATE-ACCESS"
         "OSCOPE_DURABLE_S3_SECRET_KEY" "PRIVATE-SECRET"
         "OSCOPE_DURABLE_S3_SESSION_TOKEN" "PRIVATE-SESSION"
         "OSCOPE_DURABLE_S3_MAX_ATTEMPTS" "4"
         "OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS" "2500"
         "OSCOPE_DURABLE_S3_TIMEOUT_MS" "45000"}
        result (s3-options environment calls)]
    (is (= {:vendor "chdb-durable"
            :namespace-backend test-s3-backend
            :object-id "telemetry-prod"}
           (select-keys (:db-spec result)
                        [:vendor :namespace-backend :object-id])))
    (is (= [[:s3
             {:endpoint "https://s3.example.test"
              :bucket "observability"
              :prefix "tenant/blue"
              :region "us-west-2"
              :access-key "PRIVATE-ACCESS"
              :secret-key "PRIVATE-SECRET"
              :session-token "PRIVATE-SESSION"
              :max-attempts 4
              :connect-timeout-ms 2500
              :timeout-ms 45000}]]
           @calls))))

(deftest durable-s3-environment-defaults-object-and-transport-policy
  (let [calls (atom [])
        result
        (s3-options
         {"OSCOPE_DURABLE_BACKEND" "s3"
          "OSCOPE_DURABLE_S3_ENDPOINT" "http://127.0.0.1:9000"
          "OSCOPE_DURABLE_S3_BUCKET" "observability"
          "OSCOPE_DURABLE_S3_REGION" "us-east-1"
          "OSCOPE_DURABLE_S3_ACCESS_KEY" "access"
          "OSCOPE_DURABLE_S3_SECRET_KEY" "secret"}
         calls)]
    (is (= "oscope" (get-in result [:db-spec :object-id])))
    (is (= {:prefix "" :session-token nil :max-attempts 3
            :connect-timeout-ms 10000 :timeout-ms 300000}
           (select-keys (second (first @calls))
                        [:prefix :session-token :max-attempts
                         :connect-timeout-ms :timeout-ms])))))

(deftest invalid-configuration-fails-before-backend-creation
  (doseq [[label environment]
          [["missing root" {}]
           ["invalid port" {"OSCOPE_DURABLE_ROOT" "/d" "OSCOPE_PORT" "x"}]
           ["remote host" {"OSCOPE_DURABLE_ROOT" "/d"
                            "OSCOPE_HOST" "0.0.0.0"}]
           ["zero ttl" {"OSCOPE_DURABLE_ROOT" "/d"
                         "OSCOPE_DURABLE_LEASE_TTL_MS" "0"}]
           ["heartbeat too slow"
            {"OSCOPE_DURABLE_ROOT" "/d"
             "OSCOPE_DURABLE_LEASE_TTL_MS" "30"
             "OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS" "11"}]
           ["negative skew" {"OSCOPE_DURABLE_ROOT" "/d"
                              "OSCOPE_DURABLE_CLOCK_SKEW_MS" "-1"}]
           ["invalid force" {"OSCOPE_DURABLE_ROOT" "/d"
                              "OSCOPE_DURABLE_FORCE" "sometimes"}]
           ["invalid database" {"OSCOPE_DURABLE_ROOT" "/d"
                                 "OSCOPE_DURABLE_DATABASE" "bad-name"}]
           ["invalid backend" {"OSCOPE_DURABLE_BACKEND" "ftp"}]
           ["missing S3 endpoint"
            {"OSCOPE_DURABLE_BACKEND" "s3"}]
           ["invalid object id"
            {"OSCOPE_DURABLE_BACKEND" "s3"
             "OSCOPE_DURABLE_OBJECT_ID" "../other"}]
           ["too many S3 attempts"
            {"OSCOPE_DURABLE_BACKEND" "s3"
             "OSCOPE_DURABLE_S3_MAX_ATTEMPTS" "9"}]]]
    (testing label
      (let [calls (atom [])]
        (is (thrown? Exception (options environment calls)))
        (is (empty? @calls))))))

(deftest startup-failures-have-bounded-operator-diagnostics
  (doseq [[type category]
          [[::control/lease-held :lease-held]
           [::control/lease-fenced :lease-fenced]
           [::head/corrupt :corrupt-head]
           [::durable/engine-incompatible :engine-incompatible]
           [::durable-s3/authentication :storage-authentication]
           [::durable-s3/permission :storage-permission]
           [::durable-s3/throttled :storage-throttled]
           [::durable-s3/transport :storage-transport]
           [::durable-s3/provider :storage-provider]
           [::durable-s3/invalid-response :storage-response]
           [::durable-s3/invalid-options :storage-configuration]
           [::durable-s3/invalid-key :storage-configuration]]]
    (let [diagnostic (durable-main/failure-diagnostic (wrapped-error type))]
      (is (= category (:category diagnostic)))
      (is (= #{:category :message :action} (set (keys diagnostic))))
      (is (not (re-find #"secret|private" (pr-str diagnostic))))))
  (let [diagnostic
        (durable-main/failure-diagnostic
         (ex-info "password=secret path=/private/store" {:owner "secret"}))]
    (is (= :server-failed (:category diagnostic)))
    (is (not (re-find #"secret|private|password" (pr-str diagnostic))))))

(deftest main-prints-and-throws-only-bounded-diagnostics
  (let [failure (wrapped-error ::control/lease-held)
        caught (atom nil)
        output
        (with-out-str
          (binding [*err* *out*]
            (with-redefs [durable-main/env-options (constantly {})
                          server-main/run! (fn [_] (throw failure))]
              (try
                (durable-main/-main)
                (catch Throwable error
                  (reset! caught error))))))]
    (is (not (identical? failure @caught)))
    (is (nil? (ex-cause @caught)))
    (is (= {:oscope.durable-server/error true :category :lease-held}
           (ex-data @caught)))
    (is (re-find #"failed \[lease-held\]" output))
    (is (re-find #"operator action:" output))
    (is (not (re-find #"secret|private|password"
                      (str output " " (ex-message @caught) " "
                           (pr-str (ex-data @caught))))))))
