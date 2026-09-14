(ns oscope.dependency-resolution-test
  "Checks resolved OTel/exporter roots rather than trusting declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]))

(def ^:private exporter-root
  (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/"
       "812957b85ea3717b28ad0e7a101a483f8a5f6deb/"))

(def ^:private otel-root
  (str "https___github.com_casselc_otel.git/"
       "87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0/"))

(def ^:private prior-exporter-root
  (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/"
       "05d50af479bd60588cb30d282598a505dbafaafe/"))

(def ^:private prior-exporter-coordinate
  (str "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse "
       "{:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" "
       ":git/sha \"05d50af479bd60588cb30d282598a505dbafaafe\"}}}"))

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
      (is (exact-resolution? (:out result) "jolt-otel-clickhouse.git"
                             exporter-root))
      (is (exact-coordinate? (:out result) "casselc_otel.git" otel-root)))))

(deftest prior-exporter-coordinate-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-exporter-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (testing "the real mutation resolves the prior exporter coordinate"
        (is (exact-resolution? (:out result) "jolt-otel-clickhouse.git"
                               prior-exporter-root)))
      (testing "the reviewed exporter-root oracle rejects that resolution"
        (is (false? (exact-resolution? (:out result)
                                       "jolt-otel-clickhouse.git"
                                       exporter-root)))))))
