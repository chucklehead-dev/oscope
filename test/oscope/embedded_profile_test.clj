(ns oscope.embedded-profile-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]))

(def ^:private embedded-profile-dir "profiles/embedded")
(def ^:private minimal-fixture-dir "test/fixtures/minimal-embedded-app")
(def ^:private samizdat-fixture-dir "test/fixtures/samizdat-current-graph")

(def ^:private expected-profile-deps
  '#{io.github.casselc/otel
     io.github.chucklehead-dev/jolt-chdb
     io.github.chucklehead-dev/jolt-otel-clickhouse
     metosin/malli
     org.clojure/data.json})

(def ^:private coordinates
  {:otel ["casselc_otel.git"
          "https___github.com_casselc_otel.git/0c50b0f8254713ce9df8a3f201f345b1854000b8/"]
   :chdb ["chucklehead-dev_jolt-chdb.git"
          "https___github.com_chucklehead-dev_jolt-chdb.git/dbc2db22130c7e783739c79bc24691dcbba21906/"]
   :clickhouse ["jolt-otel-clickhouse"
                ["https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/14a2998a27f64a9bff329811461be9157a00c849/"
                 "io.github.chucklehead-dev/jolt-otel-clickhouse/14a2998a27f64a9bff329811461be9157a00c849/"]]
   :data-json ["casselc_data.json.git"
               "https___github.com_casselc_data.json.git/932444043c0c06f9e295ba4963419b2481e9dd07/"]
   :casselc-db ["casselc_db.git"
                "https___github.com_casselc_db.git/a5bf25d9e141e8dcf28d6d07cd053fceb0019563/"]
   :samizdat-db ["jolt-lang_db/"
                 "https___github.com_jolt-lang_db/d85f391ca521da389b935c38f3d78b30eaa23208/"]
   :casselc-http ["casselc_http-client.git"
                  "https___github.com_casselc_http-client.git/eab6b78d5957f88690faf6768360572a3f185341/"]
   :samizdat-http ["jolt-lang_http-client/"
                   "https___github.com_jolt-lang_http-client/ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0/"]})

(defn- run-jolt [dir & args]
  (let [jolt-bin (or (System/getenv "JOLT_BIN") "jolt")
        child (process/process (into [jolt-bin "-Srepro"] args)
                               {:dir dir :out :string :err :string})
        result (deref child 120000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
    result))

(defn- classpath-roots [classpath fragment]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % fragment))
       vec))

(defn- exact-coordinate? [classpath [dependency expected-roots]]
  (let [paths (classpath-roots classpath dependency)
        expected-roots (if (string? expected-roots)
                         [expected-roots]
                         expected-roots)]
    (and (seq paths)
         (every? (fn [path]
                   (some #(str/includes? path %) expected-roots))
                 paths))))

(defn- namespace-providers [classpath source-path]
  (->> (str/split (str classpath) #":")
       (filter #(.isFile (java.io.File. % source-path)))
       vec))

(defn- safe-consumer-graph? [classpath]
  (and (= 1 (count (namespace-providers classpath "db/sqlite.clj")))
       (= 1 (count (namespace-providers classpath "jolt/http_client.clj")))))

(deftest embedded-profile-is-minimal-and-pinned
  (let [profile (edn/read-string
                 (slurp (str embedded-profile-dir "/deps.edn")))]
    (is (= "0.8.6" (:jolt/min-version profile)))
    (is (= ["../../src"] (:paths profile)))
    (is (= expected-profile-deps (set (keys (:deps profile)))))
    (is (= "0.20.1" (get-in profile [:deps 'metosin/malli :mvn/version])))
    (is (nil? (get-in profile [:deps 'io.github.casselc/jolt-http])))
    (is (nil? (get-in profile
                       [:deps 'io.github.chucklehead-dev/jolt-otel-viewer])))))

(deftest minimal-fixture-resolves-one-library-stack-and-one-sdk-owner
  (let [classpath-result (run-jolt minimal-fixture-dir "-Spath")]
    (is (map? classpath-result))
    (when (map? classpath-result)
      (is (zero? (:exit classpath-result)))
      (let [classpath (:out classpath-result)]
        (doseq [coordinate (map coordinates [:otel :chdb :clickhouse :data-json])]
          (is (exact-coordinate? classpath coordinate)))
        (is (exact-coordinate? classpath (:casselc-db coordinates)))
        (is (exact-coordinate? classpath (:casselc-http coordinates)))
        (is (= 1 (count (namespace-providers classpath "db/sqlite.clj"))))
        (is (= 1 (count (namespace-providers classpath
                                             "jolt/http_client.clj"))))
        (is (empty? (classpath-roots classpath "casselc_jolt-http")))
        (is (empty? (classpath-roots classpath "jolt-otel-viewer"))))))
  (let [run-result (run-jolt minimal-fixture-dir "-M:test")]
    (is (map? run-result))
    (when (map? run-result)
      (is (zero? (:exit run-result))
          (str (:out run-result) (:err run-result)))
      (is (str/includes? (:out run-result)
                         "minimal embedded fixture: PASS")))))

(deftest current-samizdat-provider-graph-is-rejected-causally
  (let [result (run-jolt samizdat-fixture-dir "-Spath")]
    (is (map? result))
    (when (map? result)
      (is (zero? (:exit result)))
      (let [classpath (:out result)]
        (testing "the live graph really contains both divergent DB providers"
          (is (exact-coordinate? classpath (:casselc-db coordinates)))
          (is (exact-coordinate? classpath (:samizdat-db coordinates)))
          (is (= 2 (count (namespace-providers classpath "db/sqlite.clj")))))
        (testing "the live graph really contains both divergent HTTP providers"
          (is (exact-coordinate? classpath (:casselc-http coordinates)))
          (is (exact-coordinate? classpath (:samizdat-http coordinates)))
          (is (= 2 (count (namespace-providers classpath
                                               "jolt/http_client.clj")))))
        (testing "qualification fails instead of selecting away interruptibility"
          (is (false? (safe-consumer-graph? classpath))))
        (testing "one partial provider fix cannot hide the other conflict"
          (is (false?
               (safe-consumer-graph?
                (str/replace classpath
                             (second (:casselc-db coordinates)) ""))))
          (is (false?
               (safe-consumer-graph?
                (str/replace classpath
                             (second (:casselc-http coordinates)) "")))))
        (testing "both owning-library convergences remove the causal conflicts"
          (is (safe-consumer-graph?
               (-> classpath
                   (str/replace (second (:casselc-db coordinates)) "")
                   (str/replace (second (:casselc-http coordinates)) "")))))))))
