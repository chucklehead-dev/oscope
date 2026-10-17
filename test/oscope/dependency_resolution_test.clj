(ns oscope.dependency-resolution-test
  "Checks resolved OTel/exporter roots rather than trusting declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]
            [oscope.child-support :as child]))

(def ^:private exporter-sha
  "f862cb1acc190cc2c03eb1b8096d76a2f15270d3")

(def ^:private chdb-root
  (str "https___github.com_chucklehead-dev_jolt-chdb.git/"
       "af3a902be39fc466246b39864cdf77879032c87a/"))

(def ^:private prior-chdb-root
  (str "https___github.com_chucklehead-dev_jolt-chdb.git/"
       "677219027d68702a18ee826b55ee1f2ccc3d172d/"))

(def ^:private otel-root
  (str "https___github.com_casselc_otel.git/"
       "12074bcfd668dec00a4e6ca2ca52a4f765a331e9/"))

(def ^:private prior-reader-policy-root
  "https___github.com_casselc_otel.git/e8435b381ee0cafd7d1cb5cb7b8639c551544042/")

(def ^:private prior-reader-policy-coordinate
  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"e8435b381ee0cafd7d1cb5cb7b8639c551544042\"}}}")

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

(def ^:private prior-exporter-sha
  "3a53e4b07ff56f0e0b12cdab4b16a01e619d5f3c")

(def ^:private prior-exporter-coordinate
  (str "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse "
       "{:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" "
       ":git/sha \"3a53e4b07ff56f0e0b12cdab4b16a01e619d5f3c\"}}}"))

(def ^:private prior-scalar-sdk-root
  "https___github.com_casselc_otel.git/8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2/")

(def ^:private prior-scalar-sdk-coordinate
  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2\"}}}")

(def ^:private prior-chdb-coordinate
  (str "{:deps {io.github.chucklehead-dev/jolt-chdb "
       "{:git/url \"https://github.com/chucklehead-dev/jolt-chdb.git\" "
       ":git/sha \"677219027d68702a18ee826b55ee1f2ccc3d172d\"}}}"))

(defn- dependency-roots [classpath dependency]
  (->> (str/split (str classpath) #":")
       (filter #(str/includes? % dependency))
       vec))

(defn- exact-resolution? [classpath dependency expected-root]
  (let [roots (dependency-roots classpath dependency)]
    (and (= 1 (count roots))
         (str/includes? (first roots) expected-root))))

(defn- exact-exporter-resolution? [classpath sha]
  (let [roots (dependency-roots classpath "jolt-otel-clickhouse")
        suffix (str "/" sha "/")]
    (and (= 1 (count roots))
         (let [root (first roots)]
           (and (or (str/includes?
                     root "io.github.chucklehead-dev/jolt-otel-clickhouse/")
                    (str/includes?
                     root "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/"))
                (str/includes? root suffix))))))

(defn- exact-coordinate? [classpath dependency expected-root]
  (let [roots (dependency-roots classpath dependency)]
    (and (seq roots)
         (every? #(str/includes? % expected-root) roots))))

(defn- includes-coordinate? [classpath dependency expected-root]
  (some #(str/includes? % expected-root)
        (dependency-roots classpath dependency)))

(defn- dependency-report [extra-args]
  (child/run! (into [(or (System/getenv "JOLT_BIN") "jolt") "-Srepro"]
                    (concat extra-args ["-Spath"]))
              {:out :string :err :string} {}))

(defn- successful-report? [result]
  (and (map? result)
       (zero? (:exit result))))

(deftest exporter-root-layouts-are-closed-and-sha-bound
  (let [coordinate (str "/cache/io.github.chucklehead-dev/"
                        "jolt-otel-clickhouse/" exporter-sha "/src")
        url-root (str "/cache/https___github.com_chucklehead-dev_"
                      "jolt-otel-clickhouse.git/" exporter-sha "/src")]
    (is (exact-exporter-resolution? coordinate exporter-sha))
    (is (exact-exporter-resolution? url-root exporter-sha))
    (is (false? (exact-exporter-resolution? coordinate prior-exporter-sha)))
    (is (false? (exact-exporter-resolution?
                 (str "/cache/unowned/jolt-otel-clickhouse/" exporter-sha "/src")
                 exporter-sha)))
    (is (false? (exact-exporter-resolution?
                 (str coordinate ":" url-root) exporter-sha)))))

(deftest reviewed-exporter-and-otel-revisions-are-the-resolved-roots
  (let [result (dependency-report [])]
    (is (successful-report? result))
    (when (map? result)
      (is (exact-exporter-resolution? (:out result) exporter-sha))
      (is (exact-coordinate? (:out result) "casselc_otel.git" otel-root))
      (is (exact-coordinate? (:out result) "casselc_http-client.git"
                             http-provider-root)))))

(deftest current-chdb-source-root-is-exact-and-prior-pin-is-rejected
  (let [current (dependency-report [])
        prior (dependency-report ["-Sdeps" prior-chdb-coordinate])]
    (is (successful-report? current))
    (when (map? current)
      (is (exact-coordinate? (:out current)
                             "chucklehead-dev_jolt-chdb.git" chdb-root)))
    (is (successful-report? prior))
    (when (map? prior)
      (testing "the real dependency override resolves the prior source root"
        (is (exact-coordinate? (:out prior)
                               "chucklehead-dev_jolt-chdb.git" prior-chdb-root)))
      (testing "the current chDB source-root receipt rejects the mutation"
        (is (false? (exact-coordinate? (:out prior)
                                       "chucklehead-dev_jolt-chdb.git" chdb-root)))))))

(deftest prior-exporter-coordinate-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-exporter-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (testing "the real mutation resolves the prior exporter coordinate"
        (is (exact-exporter-resolution? (:out result) prior-exporter-sha)))
      (testing "the reviewed exporter-root oracle rejects that resolution"
        (is (false? (exact-exporter-resolution? (:out result)
                                                exporter-sha)))))))

(deftest prior-scalar-sdk-coordinate-is-a-causal-red-control
  ;; The exporter now needs the helper in this exact SDK revision. Check the
  ;; real prior single-provider selection independently of the historical
  ;; duplicate-SDK/HTTP fixture below; -Spath does not load the incompatible app.
  (let [result (dependency-report ["-Sdeps" prior-scalar-sdk-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (is (exact-coordinate? (:out result) "casselc_otel.git"
                             prior-scalar-sdk-root))
      (is (false? (exact-coordinate? (:out result) "casselc_otel.git"
                                     otel-root))))))

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

(deftest current-json-root-is-exact-and-pre-receipt-sdk-is-rejected
  (let [current (dependency-report [])
        prior (dependency-report ["-Sdeps" prior-reader-policy-coordinate])]
    (is (successful-report? current))
    (is (exact-resolution? (:out current) "casselc_data.json.git"
                           "https___github.com_casselc_data.json.git/0f51b99101bc5e840f957c073f87b6f877309a25/src/main/clojure"))
    (is (successful-report? prior))
    (is (exact-coordinate? (:out prior) "casselc_otel.git" prior-reader-policy-root))
    (is (false? (exact-coordinate? (:out prior) "casselc_otel.git" otel-root)))))
