(ns oscope.dependency-resolution-test
  "Checks resolved OTel/exporter roots rather than trusting declarations."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]
            [oscope.child-support :as child]))

(def ^:private exporter-sha
  "6a98b94213648fcf52036d72019281fc37d0165c")

(def ^:private prior-singleton-exporter-sha
  "4a42716286177fd0c033d69deb708b29dd162933")

(def ^:private prior-singleton-exporter-coordinate
  "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse {:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" :git/sha \"4a42716286177fd0c033d69deb708b29dd162933\"}}}")

(def ^:private prior-fixed-budget-exporter-sha
  "695fa01711cc97e16c6d62dde9c8ad9cab1e1fdd")

(def ^:private chdb-root
  (str "https___github.com_chucklehead-dev_jolt-chdb.git/"
       "3314b79e8a234fafdf5231439a0323c878a40040/"))

(def ^:private prior-chdb-root
  (str "https___github.com_chucklehead-dev_jolt-chdb.git/"
       "677219027d68702a18ee826b55ee1f2ccc3d172d/"))

(def ^:private otel-root
  (str "https___github.com_casselc_otel.git/"
       "26186ce79941e84e0cc67eb148dd8dd43b4e1880/"))

(def ^:private prior-map-reuse-sdk-root
  "https___github.com_casselc_otel.git/12074bcfd668dec00a4e6ca2ca52a4f765a331e9/")

(def ^:private prior-map-reuse-sdk-coordinate
  "{:deps {io.github.casselc/otel {:git/url \"https://github.com/casselc/otel.git\" :git/sha \"12074bcfd668dec00a4e6ca2ca52a4f765a331e9\"}}}")

(def ^:private prior-map-reuse-exporter-sha
  "f862cb1acc190cc2c03eb1b8096d76a2f15270d3")

(def ^:private prior-map-reuse-exporter-coordinate
  "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse {:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" :git/sha \"f862cb1acc190cc2c03eb1b8096d76a2f15270d3\"}}}")

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

(deftest previous-published-stack-pins-are-real-selection-controls
  ;; Select the previous root commits for real, without claiming their APIs
  ;; are invalid. The current exact-pin oracle must distinguish each one.
  (doseq [[library url previous dependency expected]
          [["io.github.casselc/otel" "https://github.com/casselc/otel.git"
            "7878a2eb4340519d6843d13490a5a7c99ebade8a" "casselc_otel.git" otel-root]
           ["io.github.chucklehead-dev/jolt-chdb" "https://github.com/chucklehead-dev/jolt-chdb.git"
            "3d766fa295b0e98c30f275d0a334db92e6dd998c" "chucklehead-dev_jolt-chdb.git" chdb-root]
           ["io.github.chucklehead-dev/jolt-otel-clickhouse" "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
            "2c54b98d35464c8fc7c737b0c92a0042272b589a" "chucklehead-dev_jolt-otel-clickhouse.git"
            (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/" exporter-sha "/")]
           ["io.github.chucklehead-dev/jolt-otel-clickhouse" "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
            "60e1f69bbb06766c36ca9ef0c34af51a97e69c34" "chucklehead-dev_jolt-otel-clickhouse.git"
            (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/" exporter-sha "/")]]]
    (let [coordinate (str "{:deps {" library " {:git/url \"" url
                          "\" :git/sha \"" previous "\"}}}")
          result (dependency-report ["-Sdeps" coordinate])]
      (is (successful-report? result))
      (is (some #(str/includes? % (str "/" previous "/"))
                (dependency-roots (:out result) dependency)))
      (is (false? (boolean (exact-coordinate? (:out result) dependency expected)))))))

(deftest crypto-provider-is-explicit-and-matches-the-qualified-app-graph
  (let [result (dependency-report [])
        expected "https___github.com_casselc_jolt-crypto.git/649c993780a0f269938c9c6afa3bae7e9709bb48/"
        roots (dependency-roots (:out result) "jolt-crypto")]
    (is (successful-report? result))
    (is (= 1 (count roots)))
    (is (exact-coordinate? (:out result) "jolt-crypto" expected))
    (is (false? (boolean (exact-coordinate?
                          (str/replace (str (:out result))
                                       "649c993780a0f269938c9c6afa3bae7e9709bb48"
                                       "5effcc89a3258499a79a2a3d69edad9e7800d1bf")
                          "jolt-crypto" expected))))))

(deftest timer-only-batch-sdk-is-a-causal-red-control
  (let [prior "d93846e40b837dcecb546fb59f6cb05ca639f054"
        coordinate (str "{:deps {io.github.casselc/otel "
                        "{:git/url \"https://github.com/casselc/otel.git\" "
                        ":git/sha \"" prior "\"}}}")
        result (dependency-report ["-Sdeps" coordinate])]
    (is (successful-report? result))
    (is (exact-coordinate? (:out result) "casselc_otel.git"
                           (str "https___github.com_casselc_otel.git/" prior "/")))
    (is (false? (exact-coordinate? (:out result) "casselc_otel.git" otel-root)))))

(deftest prior-fixed-budget-exporter-is-rejected
  (let [coordinate (str "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse "
                        "{:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" "
                        ":git/sha \"" prior-fixed-budget-exporter-sha "\"}}}")
        result (dependency-report ["-Sdeps" coordinate])]
    (is (successful-report? result))
    (is (exact-exporter-resolution? (:out result) prior-fixed-budget-exporter-sha))
    (is (false? (exact-exporter-resolution? (:out result) exporter-sha)))))

(deftest ordinary-and-byte-json-roots-are-qualified-independently
  (let [root #(str "https___github.com_casselc_data.json.git/" % "/")
        ordinary (dependency-report [])
        byte (dependency-report ["-A:native-json-byte-batch"])
        prior-sha "b959a5625c28cc8571e04de5ca02a2c8902b8b5e"
        prior (dependency-report
                ["-Sdeps" (str "{:deps {org.clojure/data.json {:git/url "
                               "\"https://github.com/casselc/data.json.git\" :git/sha \""
                               prior-sha "\"}}}")])
        ordinary-root (root "0f51b99101bc5e840f957c073f87b6f877309a25")
        byte-root (root "2172061060a102ccab779f5adc5d77f8b83a81f6")]
    (doseq [report [ordinary byte prior]] (is (successful-report? report)))
    (doseq [report [ordinary byte]]
      (is (exact-exporter-resolution? (:out report) exporter-sha)))
    (is (exact-coordinate? (:out ordinary) "data.json" ordinary-root))
    (is (exact-coordinate? (:out byte) "data.json" byte-root))
    (is (exact-coordinate? (:out prior) "data.json" (root prior-sha)))
    (is (false? (exact-coordinate? (:out prior) "data.json" byte-root)))))

(deftest previous-byte-json-checkpoint-is-a-coordinate-red-control
  ;; This checks the selected dependency checkpoint, not backend ABI
  ;; compatibility, a runtime rejection or a persistence property.
  (let [sha "a0172a0f1418632c760af98ef5280dd234d21e85"
        coordinate (str "{:deps {org.clojure/data.json "
                        "{:git/url \"https://github.com/casselc/data.json.git\" "
                        ":git/sha \"" sha "\"}}}")
        result (dependency-report ["-Sdeps" coordinate])]
    (is (successful-report? result))
    (is (exact-coordinate? (:out result) "data.json"
                           (str "https___github.com_casselc_data.json.git/" sha "/")))
    (is (false? (exact-coordinate? (:out result) "data.json"
                                  "https___github.com_casselc_data.json.git/2172061060a102ccab779f5adc5d77f8b83a81f6/")))))

(deftest prior-sorted-stack-coordinates-are-causal-red-controls
  ;; These are real dependency selections, not assertions about backend ABI
  ;; rejection or about whether an older runtime supports the optional fold.
  (doseq [[library url old-sha dependency current-root]
          [["io.github.casselc/otel" "https://github.com/casselc/otel.git"
            "37c684a2c370fc82fde82331635973bce87a52b3" "casselc_otel.git" otel-root]
           ["io.github.chucklehead-dev/jolt-otel-clickhouse"
            "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
            "4d48871cf2ac7a2ad0bf6517042c624df376a9bf"
            "jolt-otel-clickhouse" (str "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/" exporter-sha "/")]
           ["org.clojure/data.json" "https://github.com/casselc/data.json.git"
            "96f3e47dba5970728866dacb5ac41a25135a2df5" "data.json"
            "https___github.com_casselc_data.json.git/2172061060a102ccab779f5adc5d77f8b83a81f6/"]]]
    (let [selection (str "{" library " {:git/url \"" url "\" :git/sha \"" old-sha "\"}}")
          byte? (= library "org.clojure/data.json")
          ;; The JSON mutation must exercise the active byte alias, with an
          ;; override that cannot be shadowed by that alias's extra-deps.
          coordinate (if byte?
                       (str "{:aliases {:native-json-byte-batch {:override-deps " selection "}}}")
                       (str "{:deps " selection "}"))
          result (dependency-report (cond-> ["-Sdeps" coordinate]
                                      byte? (conj "-A:native-json-byte-batch")))]
      (is (successful-report? result))
      (is (includes-coordinate? (:out result) dependency (str old-sha "/")))
      (is (false? (exact-coordinate? (:out result) dependency current-root))))))

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

(deftest previous-product-stack-coordinates-are-causal-red-controls
  (let [driver-sha "af3a902be39fc466246b39864cdf77879032c87a"
        previous-exporter "65f0f5b30efe58dc4c0b9a5bde989aabcee2001f"
        coordinate (fn [lib repo sha]
                     (str "{:deps {" lib " {:git/url \"https://github.com/"
                          repo ".git\" :git/sha \"" sha "\"}}}"))
        driver (dependency-report
                 ["-Sdeps" (coordinate "io.github.chucklehead-dev/jolt-chdb"
                                       "chucklehead-dev/jolt-chdb" driver-sha)])
        exporter (dependency-report
                   ["-Sdeps" (coordinate "io.github.chucklehead-dev/jolt-otel-clickhouse"
                                         "chucklehead-dev/jolt-otel-clickhouse"
                                         previous-exporter)])]
    (is (successful-report? driver))
    (is (exact-coordinate? (:out driver) "chucklehead-dev_jolt-chdb.git"
                           (str "https___github.com_chucklehead-dev_jolt-chdb.git/"
                                driver-sha "/")))
    (is (false? (exact-coordinate? (:out driver)
                                  "chucklehead-dev_jolt-chdb.git" chdb-root)))
    (is (successful-report? exporter))
    (is (exact-exporter-resolution? (:out exporter) previous-exporter))
    (is (false? (exact-exporter-resolution? (:out exporter) exporter-sha)))))

(deftest pre-bounded-admission-stack-is-a-causal-red-control
  ;; Deliberately resolve old coordinates without loading their incompatible
  ;; app. The source-root oracle must discriminate both sides of this repin.
  (let [sdk-sha "14ffd741351373f07d96aa070b0f9853d7daf8ab"
        old-exporter "0bd0c5aab7f69a3083acf548b578cdf33307b494"
        coordinate (fn [lib repo sha]
                     (str "{:deps {" lib " {:git/url \"https://github.com/"
                          repo ".git\" :git/sha \"" sha "\"}}}"))
        sdk (dependency-report
              ["-Sdeps" (coordinate "io.github.casselc/otel" "casselc/otel" sdk-sha)])
        exporter (dependency-report
                   ["-Sdeps" (coordinate "io.github.chucklehead-dev/jolt-otel-clickhouse"
                                         "chucklehead-dev/jolt-otel-clickhouse" old-exporter)])]
    (is (successful-report? sdk))
    (is (exact-coordinate? (:out sdk) "casselc_otel.git"
                           (str "https___github.com_casselc_otel.git/" sdk-sha "/")))
    (is (false? (exact-coordinate? (:out sdk) "casselc_otel.git" otel-root)))
    (is (successful-report? exporter))
    (is (exact-exporter-resolution? (:out exporter) old-exporter))
    (is (false? (exact-exporter-resolution? (:out exporter) exporter-sha)))))

(deftest previous-integrated-exporter-is-a-causal-red-control
  (let [sha "c24ffc30685d83d2eadb68d33dd18af04ffaa1bd"
        coordinate (str "{:deps {io.github.chucklehead-dev/jolt-otel-clickhouse "
                        "{:git/url \"https://github.com/chucklehead-dev/jolt-otel-clickhouse.git\" "
                        ":git/sha \"" sha "\"}}}")
        result (dependency-report ["-Sdeps" coordinate])]
    (is (successful-report? result))
    (is (exact-exporter-resolution? (:out result) sha))
    (is (false? (exact-exporter-resolution? (:out result) exporter-sha)))))

(deftest prior-exporter-coordinate-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-exporter-coordinate])]
    (is (successful-report? result))
    (when (map? result)
      (testing "the real mutation resolves the prior exporter coordinate"
        (is (exact-exporter-resolution? (:out result) prior-exporter-sha)))
      (testing "the reviewed exporter-root oracle rejects that resolution"
        (is (false? (exact-exporter-resolution? (:out result)
                                                exporter-sha)))))))

(deftest prior-singleton-exporter-is-a-causal-red-control
  (let [result (dependency-report ["-Sdeps" prior-singleton-exporter-coordinate])]
    (is (successful-report? result))
    (is (exact-exporter-resolution? (:out result) prior-singleton-exporter-sha))
    (is (false? (exact-exporter-resolution? (:out result) exporter-sha)))))

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

(deftest pre-map-reuse-stack-coordinates-are-causal-red-controls
  ;; Resolving an incompatible SDK is intentional here; never load its app.
  (let [sdk (dependency-report ["-Sdeps" prior-map-reuse-sdk-coordinate])
        exporter (dependency-report ["-Sdeps" prior-map-reuse-exporter-coordinate])]
    (is (successful-report? sdk))
    (is (exact-coordinate? (:out sdk) "casselc_otel.git" prior-map-reuse-sdk-root))
    (is (false? (exact-coordinate? (:out sdk) "casselc_otel.git" otel-root)))
    (is (successful-report? exporter))
    (is (exact-exporter-resolution? (:out exporter) prior-map-reuse-exporter-sha))
    (is (false? (exact-exporter-resolution? (:out exporter) exporter-sha)))))

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
