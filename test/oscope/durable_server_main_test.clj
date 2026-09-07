(ns oscope.durable-server-main-test
  (:require [clojure.test :refer [deftest is testing]]
            [oscope.durable-server-main :as durable-main]))

(defn- options [environment calls]
  (durable-main/durable-options
   environment
   (fn [root] (swap! calls conj root) ::backend)
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
            :backend ::backend
            :owner "collector"
            :instance "collector-17"
            :database "telemetry"
            :scratch-parent "/private/scratch"
            :lease-ttl-ms 60000
            :heartbeat-interval-ms 15000
            :clock-skew-ms 250
            :force? true}
           (:db-spec result)))))

(deftest durable-environment-defaults-identity-and-lease-policy
  (let [calls (atom [])
        result (options {"OSCOPE_DURABLE_ROOT" "/durable"} calls)]
    (is (= "oscope" (get-in result [:db-spec :owner])))
    (is (= "generated-instance" (get-in result [:db-spec :instance])))
    (is (= "default" (get-in result [:db-spec :database])))
    (is (= 30000 (get-in result [:db-spec :lease-ttl-ms])))
    (is (nil? (get-in result [:db-spec :heartbeat-interval-ms])))
    (is (false? (get-in result [:db-spec :force?])))))

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
                                 "OSCOPE_DURABLE_DATABASE" "bad-name"}]]]
    (testing label
      (let [calls (atom [])]
        (is (thrown? Exception (options environment calls)))
        (is (empty? @calls))))))
