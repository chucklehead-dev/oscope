(ns oscope.typed-query-storage-report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [oscope.typed-query-storage-report :as report]))

(defn- coverage [mode]
  (merge {:valid 0 :present-empty 0 :absent 7 :invalid 0
          :historical-untyped-fallback 0
          :historical-untyped-unavailable 0 :total 32}
         (if (= :typed mode)
           {:valid 25}
           {:historical-untyped-fallback 25})))

(defn- latency [count]
  (report/latency-summary (vec (repeat count 1.0))))

(defn- case-result [mode]
  {:mode mode :row-count 32 :int64-cardinality 4
   :distribution {:missing-every 5}
   :phases {:setup {:elapsed-ms 1.0}
            :ingest {:elapsed-ms 2.0 :batches 2 :rows-per-second 16000.0
                     :batch-latency (latency 2)}
            :reopen {:elapsed-ms 1.0}}
   :queries (mapv (fn [operation]
                    {:operation operation :first-after-reopen-ms 1.0
                     :warm-latency (latency 5)})
                  [:int64-filter :boolean-filter :int64-aggregate])
   :storage {:method :system-parts-active :table "otel_traces"
             :optimized-final? true :part-count 1 :rows 32
             :bytes-on-disk (if (= :typed mode) 100 80)
             :compressed-bytes 60 :uncompressed-bytes 120}
   :correctness {:schema-binding-confirmed? true :row-count-confirmed? true
                 :present-count 25 :absent-count 7
                 :int64-match-count 6 :boolean-match-count 12
                 :int64-filter-coverage (coverage mode)
                 :boolean-filter-coverage (coverage mode)
                 :int64-aggregate-coverage (coverage mode)
                 :int64-aggregate {:count 25 :min 0 :max 3 :avg 1.48}}})

(defn- provenance []
  {:source-sha "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :source-sha-source :clean-worktree-runner :runtime :jolt
   :source-worktree-state :clean
   :jolt-version "v0.8.3" :clojure-version "1.11.0-jolt"
   :scheme-version "10.4.1" :machine-type "ta6le" :os-name "Linux"
   :os-arch "amd64" :native-chdb-version "26.7.0"
   :declared-chdb-version "26.7.0" :dependency-pins report/dependency-pins
   :transport :otlp-http-json :cache-states [:first-after-reopen :warmed]
   :cold-cache-claimed? false})

(defn- valid-shard [index count]
  {:schema report/shard-schema-version
   :benchmark :typed-query-storage :qualification :smoke
   :configuration {:row-counts [32] :cardinalities [4] :batch-size 16
                   :missing-every 5 :query-warmups 1 :query-samples 5
                   :result-limit 100}
   :provenance (provenance)
   :repetition {:index index :count count
                :mode-order (report/mode-order-for index)}
   :cases (mapv case-result (report/mode-order-for index))
   :comparison [{:row-count 32 :int64-cardinality 4
                 :method :system-parts-active :typed-bytes 100
                 :string-fallback-bytes 80 :typed-to-fallback-ratio 1.25
                 :results-equal? true}]})

(defn- valid-report []
  (report/combine-shards! [(valid-shard 0 2) (valid-shard 1 2)]))

(deftest percentile-support-is-sample-counted
  (is (= #{:p50-ms}
         (set (filter #(contains? #{:p50-ms :p95-ms :p99-ms} %)
                      (keys (latency 5))))))
  (is (= #{:p50-ms :p95-ms}
         (set (filter #(contains? #{:p50-ms :p95-ms :p99-ms} %)
                      (keys (latency 20))))))
  (is (= #{:p50-ms :p95-ms :p99-ms}
         (set (filter #(contains? #{:p50-ms :p95-ms :p99-ms} %)
                      (keys (latency 100)))))))

(deftest direct-dependency-provenance-matches-the-benchmark-contract
  (let [deps (:deps (edn/read-string (slurp "deps.edn")))
        observed {:otel (get-in deps ['io.github.casselc/otel :git/sha])
                  :jolt-http
                  (get-in deps ['io.github.casselc/jolt-http :git/sha])
                  :jolt-chdb
                  (get-in deps ['io.github.chucklehead-dev/jolt-chdb :git/sha])
                  :jolt-otel-clickhouse
                  (get-in deps ['io.github.chucklehead-dev/jolt-otel-clickhouse
                                :git/sha])
                  :jolt-otel-viewer
                  (get-in deps ['io.github.chucklehead-dev/jolt-otel-viewer
                                :git/sha])
                  :malli (get-in deps ['metosin/malli :mvn/version])
                  :data-json
                  (get-in deps ['org.clojure/data.json :git/sha])}]
    (is (= report/dependency-pins observed))))

(deftest cross-mode-comparison-is-a-causal-result-oracle
  (let [typed {:report (case-result :typed)
               :oracle {:int64-filter-ids [["a" "b"]]
                        :boolean-filter-ids [["a" "b"]]
                        :int64-aggregate {:count 1 :min 1 :max 1 :avg 1.0}}}
        fallback (assoc typed :report (case-result :string-fallback))]
    (is (= true (:results-equal? (first (report/compare-results!
                                         [typed fallback])))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (report/compare-results!
                  [typed (assoc-in fallback [:oracle :int64-aggregate :max] 2)])))))

(deftest process-repetition-order-is-causal-and-honest
  (let [single (report/combine-shards! [(valid-shard 0 1)])
        balanced (valid-report)]
    (is (false? (get-in single [:execution :counterbalanced?])))
    (is (= [{:repetition-index 0
             :mode-order [:string-fallback :typed]}]
           (get-in single [:execution :mode-orders])))
    (is (true? (get-in balanced [:execution :counterbalanced?])))
    (is (= [{:repetition-index 0
             :mode-order [:string-fallback :typed]}
            {:repetition-index 1
             :mode-order [:typed :string-fallback]}]
           (get-in balanced [:execution :mode-orders])))
    (doseq [[label operation]
            [["missing second process repetition"
              #(report/combine-shards! [(valid-shard 0 2)])]
             ["duplicate repetition index"
              #(report/combine-shards! [(valid-shard 0 2) (valid-shard 0 2)])]
             ["process repetitions disagree about declared count"
              #(report/combine-shards!
                [(valid-shard 0 2) (valid-shard 1 4)])]
             ["biased claimed mode order"
              #(report/validate-shard!
                (assoc-in (valid-shard 1 2) [:repetition :mode-order]
                          [:string-fallback :typed]))]
             ["actual case order differs from claimed order"
              #(report/validate-shard!
                (update (valid-shard 1 2) :cases
                        (fn [cases] (vec (reverse cases)))))]
             ["stale process provenance"
              #(report/combine-shards!
                [(valid-shard 0 2)
                 (assoc-in (valid-shard 1 2) [:provenance :source-sha]
                           "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")])]
             ["single run falsely claims counterbalance"
              #(report/validate-report!
                (assoc-in single [:execution :counterbalanced?] true))]
             ["combined report omits a run"
              #(report/validate-report! (update balanced :runs pop))]
             ["combined report omits its repetition count"
              #(report/validate-report!
                (assoc-in balanced [:execution :process-repetitions] nil))]
             ["combined report carries stale order metadata"
              #(report/validate-report!
                (assoc-in balanced [:execution :mode-orders 1 :mode-order]
                          [:string-fallback :typed]))]]]
      (testing label
        (is (thrown? clojure.lang.ExceptionInfo (operation)))))))

(deftest artifact-writer-accepts-a-current-directory-path
  (let [path (str ".typed-query-storage-report-test-"
                  (System/nanoTime) ".edn")
        file (java.io.File. path)
        artifact (valid-report)]
    (try
      (is (= artifact
             (report/write-artifact! path artifact report/validate-report!)))
      (is (.isFile file))
      (finally
        (.delete file)))))

(deftest benchmark-runner-preserves-the-last-validated-artifact
  (let [runner (slurp "scripts/run-typed-query-storage-benchmark.sh")]
    (is (not (.contains runner "rm -f -- \"$final_output\""))
        "a failed replacement run must not delete the previous artifact")
    (is (.contains runner
                   "mv -- \"$run_dir/combined.edn\" \"$final_output\"")
        "only the validated combined report atomically replaces the artifact")))

(deftest report-contract-has-causal-red-controls
  (is (= (valid-report) (report/validate-report! (valid-report))))
  (doseq [[label mutant]
          [["non-finite timing"
            (assoc-in (valid-report)
                      [:runs 0 :cases 0 :phases :setup :elapsed-ms] ##Inf)]
           ["unsupported p99 claim"
            (assoc-in (valid-report)
                      [:runs 0 :cases 0 :queries 0 :warm-latency :p99-ms] 1.0)]
           ["coverage does not conserve total"
            (update-in (valid-report)
                       [:runs 0 :cases 0 :correctness :int64-filter-coverage
                        :historical-untyped-fallback] dec)]
           ["one comparison mode is missing"
            (update-in (valid-report) [:runs 0 :cases] pop)]
           ["storage row count does not match fixture"
            (assoc-in (valid-report) [:runs 0 :cases 0 :storage :rows] 31)]
           ["configured batch count is not the measured batch count"
            (assoc-in (valid-report) [:configuration :batch-size] 15)]
           ["configured sample count is not the measured sample count"
            (assoc-in (valid-report) [:configuration :query-samples] 6)]
           ["configured fixture distribution differs from measured cases"
            (assoc-in (valid-report) [:configuration :missing-every] 4)]
           ["unqualified filesystem footprint replaces system.parts"
            (assoc-in (valid-report)
                      [:runs 0 :cases 0 :storage :method] :directory-sum)]
           ["dependency pin is stale"
            (assoc-in (valid-report) [:provenance :dependency-pins :otel]
                      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")]
           ["source revision is abbreviated"
            (assoc-in (valid-report) [:provenance :source-sha] "abcdef12")]
           ["runner reports a dirty worktree"
            (assoc-in (valid-report) [:provenance :source-worktree-state] :dirty)]
           ["cross-mode result equality is unconfirmed"
            (assoc-in (valid-report)
                      [:runs 0 :comparison 0 :results-equal?] false)]
           ["reported storage ratio is not derived from case bytes"
            (assoc-in (valid-report)
                      [:runs 0 :comparison 0 :typed-to-fallback-ratio]
                      1.0)]
           ["aggregate timing is silently replaced by a duplicate filter"
            (assoc-in (valid-report)
                      [:runs 0 :cases 0 :queries 2 :operation]
                      :int64-filter)]]]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo
                   (report/validate-report! mutant))))))
