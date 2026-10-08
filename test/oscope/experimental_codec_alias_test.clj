(ns oscope.experimental-codec-alias-test
  "Explicit Git-stack gate; run only with :experimental-byte-collector active."
  (:require [clojure.test :as test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private expected
  {'io.github.casselc/otel ["19fc49d20b3a75906f0ccbb8b50c7e48b03e4813" "otel/any_value.clj"]
   'jolt-lang/jolt-crypto ["5effcc89a3258499a79a2a3d69edad9e7800d1bf" "jolt/crypto.clj"]
   'io.github.chucklehead-dev/jolt-chdb ["cd5a3fb520a6f4144e07db4bae0c1909b261c3b1" "jdbc/chdb.clj"]
   'io.github.chucklehead-dev/jolt-otel-clickhouse ["51c225f04e12bd08202b3990598a5e4aaeb8fd17" "otel/exporter/chdb.clj"]
   'org.clojure/data.json ["2fa6ddded8050bb2fc16c28056777e433a4542e4" "clojure/data/json.clj"]})

(deftest aliases-are-identical-exact-git-stacks-without-default-repin
  (let [root (edn/read-string (slurp "deps.edn"))
        profile (edn/read-string (slurp "profiles/embedded/deps.edn"))
        selected (get-in root [:aliases :experimental-byte-collector :extra-deps])]
    (is (= selected (get-in profile [:aliases :experimental-byte-collector :extra-deps])))
    (is (= (set (keys expected)) (set (keys selected))))
    (doseq [[library [sha _]] expected]
      (is (= sha (get-in selected [library :git/sha])))
      (is (string? (get-in selected [library :git/url])))
      (is (not (contains? (get selected library) :local/root))))
    (is (= ['jolt-lang/jolt-crypto] (get-in selected ['io.github.casselc/otel :exclusions])))
    (is (= "8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2"
           (get-in root [:deps 'io.github.casselc/otel :git/sha])))
    (is (= "64293b131c7e1c06d6e4ec4794a219c91f3b3b90"
           (get-in root [:deps 'io.github.chucklehead-dev/jolt-chdb :git/sha])))))

(deftest active-stack-resolves-published-sources-not-local-overrides
  (doseq [[_ [sha resource]] expected]
    (let [resolved (some-> (io/resource resource) str)]
      (is (and resolved (str/includes? resolved sha)) (str "Wrong source for " resource))
      (is (and resolved (not (str/includes? resolved "/worktrees/")))
          (str "Local override for " resource)))))

(deftest active-stack-includes-the-native-projection-resource
  (let [resource "otel/exporter/chdb/native_attributes.ss"
        resolved (some-> (io/resource resource) str)]
    (is (and resolved (str/includes? resolved "51c225f04e12bd08202b3990598a5e4aaeb8fd17")))
    (is (and resolved (not (str/includes? resolved "/worktrees/"))))))

(defn -main [& _]
  (let [result (test/run-tests 'oscope.experimental-codec-alias-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
