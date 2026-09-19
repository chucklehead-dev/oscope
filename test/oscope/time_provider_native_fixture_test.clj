(ns oscope.time-provider-native-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private fixture-root "test/fixtures/time-provider-durable")
(def ^:private canonical-url "https://github.com/chucklehead-dev/time.git")
(def ^:private canonical-sha "2494b21b25cd959573c3e6050cd475e4bf302fdb")
(def ^:private chdb-url "https://github.com/chucklehead-dev/jolt-chdb.git")
(def ^:private chdb-sha "adaa779e1af3e58f1d7a552d79d074630bfbf815")

(defn- deps [fixture]
  (edn/read-string (slurp (str fixture-root "/" fixture "/deps.edn"))))

(defn- time-coordinate [profile coordinate]
  (get-in profile [:deps coordinate]))

(deftest producer-reader-declare-the-same-two-logical-time-identities
  (doseq [fixture ["producer" "reader"]]
    (let [profile (deps fixture)]
      (doseq [coordinate ['jolt-lang/time 'io.github.jolt-lang/time]]
        (is (= {:git/url canonical-url :git/sha canonical-sha}
               (time-coordinate profile coordinate)))))))

(deftest duplicate-control-has-one-and-only-one-coordinate-mutation
  (let [producer (deps "producer")
        duplicate (deps "duplicate")]
    (is (= (dissoc (:deps producer) 'io.github.jolt-lang/time)
           (dissoc (:deps duplicate) 'io.github.jolt-lang/time)))
    (is (= canonical-url
           (get-in duplicate [:deps 'jolt-lang/time :git/url])))
    (is (= canonical-sha
           (get-in duplicate [:deps 'jolt-lang/time :git/sha])))
    (is (= "https://github.com/jolt-lang/time.git"
           (get-in duplicate [:deps 'io.github.jolt-lang/time :git/url])))
    (is (not= canonical-sha
              (get-in duplicate [:deps 'io.github.jolt-lang/time :git/sha])))))

(deftest qualification-runner-checks-the-clj-cljc-union-before-build
  (let [source (slurp "test/run_time_provider_native_fixture.sh")]
    (is (str/includes? source "jolt/time.clj"))
    (is (str/includes? source "jolt/time.cljc"))
    ;; The executable gate observes resolved physical roots, not merely the
    ;; checked-in EDN data covered by the preceding controls.
    (is (str/includes? source "check_single_canonical_provider"))
    (is (str/includes? source "check_expected_duplicate_providers"))
    (is (str/includes? source "https___github.com_chucklehead-dev_time.git/2494b21b25cd959573c3e6050cd475e4bf302fdb/src"))
    (is (str/includes? source "https___github.com_jolt-lang_time.git/70dfb7981ef4ed70c5d142109d56a90edfb79cef/src"))
    (is (str/includes? source "build -m time-provider-durable.producer"))
    (is (str/includes? source "build -m time-provider-durable.reader"))))

(deftest fixtures-load-the-public-jdbc-shim-before-clojure-jdbc
  ;; jdbc.core's generic map implementation is already compiled if db.jdbc is
  ;; omitted. The public shim must take precedence before either fixture opens
  ;; the Durable `:vendor` dbspec.
  (doseq [path ["producer/src/time_provider_durable/producer.clj"
                "reader/src/time_provider_durable/reader.clj"]]
    (let [source (slurp (str fixture-root "/" path))]
      (is (str/includes? source "[db.jdbc]"))
      (is (< (.indexOf source "[db.jdbc]")
             (.indexOf source "[jdbc.core :as jdbc]"))))))

(deftest fixtures-use-the-simple-public-backend-dbspec-shape
  (doseq [path ["producer/src/time_provider_durable/producer.clj"
                "reader/src/time_provider_durable/reader.clj"]]
    (let [source (slurp (str fixture-root "/" path))]
      (is (str/includes? source ":backend store"))
      (is (str/includes? source ":scratch-parent root"))
      (is (not (str/includes? source ":namespace-backend store"))))))

(deftest builds-embed-the-qualified-pinned-chdb-resource-root
  ;; A Jolt binary must explicitly embed this dependency resource. The native
  ;; workflow has already checked out this exact chDB SHA at this path; copying
  ;; abi.edn into the fixture would create a second, drift-prone ABI authority.
  (doseq [fixture ["producer" "reader" "duplicate"]]
    (is (= ["../../../../.qualification/jolt-chdb/resources"]
           (get-in (deps fixture) [:jolt/build :embed])))))

(deftest fixtures-and-native-workflow-select-the-same-chdb-source
  ;; The compiled code and embedded ABI descriptor must originate at one exact
  ;; reviewed checkout. A matching resource path alone cannot prove that.
  (doseq [fixture ["producer" "reader" "duplicate"]]
    (is (= {:git/url chdb-url :git/sha chdb-sha}
           (get-in (deps fixture) [:deps 'io.github.chucklehead-dev/jolt-chdb]))))
  (let [workflow (slurp ".github/workflows/embedded-native-profile.yml")]
    (is (str/includes? workflow "repository: chucklehead-dev/jolt-chdb"))
    (is (str/includes? workflow (str "ref: " chdb-sha)))
    (is (str/includes? workflow "path: .qualification/jolt-chdb"))))
