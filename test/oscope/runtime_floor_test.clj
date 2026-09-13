(ns oscope.runtime-floor-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private runtime-floor "0.8.6")
(def ^:private durable-sha
  "dbc2db22130c7e783739c79bc24691dcbba21906")
(def ^:private aspect-sha
  "3773a67801bdcbd63c6484f95fa07a4b8afddb72")
(def ^:private aspect-compiler-sha
  "f00bc93bdd8274b14087b74272aadeffb60e0447")

(defn- release-workflow-at-floor? [workflow]
  (and (str/includes?
        workflow
        (str "https://raw.githubusercontent.com/jolt-lang/jolt/v"
             runtime-floor "/install"))
       (str/includes? workflow (str "--version " runtime-floor))
       (str/includes? workflow (str "\"jolt v" runtime-floor "\""))
       (not (str/includes? workflow "/jolt/v0.8.3/install"))
       (not (str/includes? workflow "--version 0.8.3"))
       (not (str/includes? workflow "\"jolt v0.8.3\""))))

(defn- qualification-at-floor? [deps workflows]
  (and (= runtime-floor (:jolt/min-version deps))
       (every? release-workflow-at-floor? workflows)))

(deftest source-documentation-and-hosted-runtimes-share-one-floor
  (let [deps (edn/read-string (slurp "deps.edn"))
        readme (slurp "README.md")
        tests-workflow (slurp ".github/workflows/tests.yml")
        s3-workflow (slurp ".github/workflows/durable-s3-e2e.yml")
        aws-workflow (slurp ".github/workflows/durable-aws.yml")]
    (is (qualification-at-floor?
         deps [tests-workflow s3-workflow aws-workflow]))
    (is (str/includes? readme "requires Jolt v0.8.6 or newer"))
    (doseq [workflow [tests-workflow s3-workflow aws-workflow]]
      (is (release-workflow-at-floor? workflow)))
    (is (str/includes? tests-workflow ".jolt-cache-v0.8.6"))
    (is (str/includes? tests-workflow ".jolt-gitlibs-v0.8.6"))
    (is (str/includes? s3-workflow ".jolt-cache-v0.8.6"))
    (is (str/includes? s3-workflow ".jolt-gitlibs-v0.8.6"))))

(deftest durable-revisions-remain-at-the-prior-qualified-boundary
  (let [deps (edn/read-string (slurp "deps.edn"))
        s3-workflow (slurp ".github/workflows/durable-s3-e2e.yml")]
    (is (= durable-sha
           (get-in deps [:deps 'io.github.chucklehead-dev/jolt-chdb
                         :git/sha])))
    (is (str/includes? s3-workflow durable-sha))
    (is (str/includes? s3-workflow aspect-sha))
    (is (str/includes? s3-workflow aspect-compiler-sha))))

(deftest downgrade-mutations-are-rejected-causally
  (let [deps (edn/read-string (slurp "deps.edn"))
        workflow (slurp ".github/workflows/tests.yml")]
    (testing "a declared 0.8.3 source floor fails the exact floor oracle"
      (is (false?
           (qualification-at-floor?
            (assoc deps :jolt/min-version "0.8.3") [workflow]))))
    (testing "a hosted 0.8.3 install fails the complete workflow oracle"
      (is (false?
           (release-workflow-at-floor?
            (str/replace workflow "0.8.6" "0.8.3")))))))
