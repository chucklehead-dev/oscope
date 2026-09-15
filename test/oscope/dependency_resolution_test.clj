(ns oscope.dependency-resolution-test
  "Checks resolved OTel/exporter roots rather than trusting declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]))

(def ^:private exporter-root
  (str "io.github.chucklehead-dev/jolt-otel-clickhouse/"
       "14a2998a27f64a9bff329811461be9157a00c849/"))

(def ^:private otel-root
  (str "https___github.com_casselc_otel.git/"
       "0c50b0f8254713ce9df8a3f201f345b1854000b8/"))

(def ^:private prior-otel-root
  (str "https___github.com_casselc_otel.git/"
       "87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0/"))

(def ^:private http-provider-root
  (str "https___github.com_casselc_http-client.git/"
       "eab6b78d5957f88690faf6768360572a3f185341/"))

(def ^:private prior-http-provider-root
  (str "https___github.com_casselc_http-client.git/"
       "9cb5801e8c5929387715aa6713c33b2c21fd9a2a/"))

(def ^:private prior-otel-coordinate
  (str "{:deps {oscope.mutation/prior-otel "
       "{:git/url \"https://github.com/casselc/otel.git\" "
       ":git/sha \"87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0\"} "
       "oscope.mutation/prior-http-provider "
       "{:git/url \"https://github.com/casselc/http-client.git\" "
       ":git/sha \"9cb5801e8c5929387715aa6713c33b2c21fd9a2a\"}}}"))

(def ^:private prior-exporter-root
  (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/"
       "96e68eddbe897e566ec3a7564609c49b0794e59d/"))

(def ^:private prior-exporter-coordinate
  (str "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse "
       "{:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" "
       ":git/sha \"96e68eddbe897e566ec3a7564609c49b0794e59d\"}}}"))

(defn- dependency-roots [classpath dependency]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % dependency))
       vec))

(defn- exact-resolution? [classpath dependency expected-root]
  (let [roots (dependency-roots classpath dependency)]
    (and (= 1 (count roots))
         (str/includes? (first roots) expected-root))))

(defn- exact-coordinate? [classpath dependency expected-root]
  (let [roots (dependency-roots classpath dependency)]
    (and (seq roots)
         (every? #(str/includes? % expected-root) roots))))

(defn- includes-coordinate? [classpath dependency expected-root]
  (some #(str/includes? % expected-root)
        (dependency-roots classpath dependency)))

(defn- dependency-report [extra-args]
  (let [jolt-bin (or (System/getenv "JOLT_BIN") "jolt")
        child (process/process (into [jolt-bin "-Srepro"]
                                     (concat extra-args ["-Spath"]))
                               {:out :string :err :string})
        result (deref child 120000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil)))
    result))

(defn- successful-report? [result]
  (and (map? result)
       (zero? (:exit result))))

(deftest reviewed-exporter-and-otel-revisions-are-the-resolved-roots
  (let [result (dependency-report [])]
    (is (successful-report? result))
    (when (map? result)
      (is (exact-resolution? (:out result) "io.github.chucklehead-dev/jolt-otel-clickhouse"
                             exporter-root))
      (is (exact-coordinate? (:out result) "casselc_otel.git" otel-root))
      (is (exact-coordinate? (:out result) "casselc_http-client.git"
                             http-provider-root)))))

(deftest prior-exporter-coordinate-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-exporter-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (testing "the real mutation resolves the prior exporter coordinate"
        (is (exact-resolution? (:out result) "jolt-otel-clickhouse.git"
                               prior-exporter-root)))
      (testing "the reviewed exporter-root oracle rejects that resolution"
        (is (false? (exact-resolution? (:out result)
                                       "io.github.chucklehead-dev/jolt-otel-clickhouse"
                                       exporter-root)))))))

(deftest prior-otel-coordinate-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-otel-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (testing "the real mutation adds the prior OTel and HTTP provider roots"
        (is (includes-coordinate? (:out result) "casselc_otel.git"
                                  prior-otel-root))
        (is (includes-coordinate? (:out result) "casselc_http-client.git"
                                  prior-http-provider-root)))
      (testing "the reviewed OTel-root oracle rejects that resolution"
        (is (false? (exact-coordinate? (:out result)
                                       "casselc_otel.git"
                                       otel-root)))
        (is (false? (exact-coordinate? (:out result)
                                       "casselc_http-client.git"
                                       http-provider-root)))))))
