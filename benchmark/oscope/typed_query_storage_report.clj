(ns oscope.typed-query-storage-report
  "Closed, bounded report contract for the typed query/storage benchmark."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def schema-version 2)
(def shard-schema-version 1)
(def max-process-repetitions 10)
(def minimum-percentile-samples {:p50 5 :p95 20 :p99 100})
(def modes #{:typed :string-fallback})
(def operations #{:int64-filter :boolean-filter :int64-aggregate})
(def dependency-pins
  {:otel "87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0"
   :jolt-http "35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8"
   :jolt-chdb "dbc2db22130c7e783739c79bc24691dcbba21906"
   :jolt-otel-clickhouse "03b75ae1c6cdba107584827e950e2048ab322578"
   :jolt-otel-viewer "5723a7c28c3bb3ae7cb27f9856b90463e77df523"
   :malli "0.20.1"
   :data-json "932444043c0c06f9e295ba4963419b2481e9dd07"})

(defn- fail! [message data]
  (throw (ex-info message
                  (assoc data :oscope.typed-query-storage-report/error true))))

(defn finite-number? [value]
  (and (number? value)
       (let [number (double value)]
         (and (= number number) (not= number ##Inf) (not= number ##-Inf)))))

(defn- nonnegative-number? [value]
  (and (finite-number? value) (not (neg? value))))

(defn- sha? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{40}" value))))

(defn- nonblank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn valid-repetition-count? [value]
  (and (integer? value) (pos? value) (<= value max-process-repetitions)
       (or (= 1 value) (even? value))))

(defn mode-order-for [repetition-index]
  (if (even? repetition-index)
    [:string-fallback :typed]
    [:typed :string-fallback]))

(defn- quantile [ordered probability]
  (nth ordered (max 0 (dec (long (Math/ceil (* probability (count ordered))))))))

(defn latency-summary
  "Summarize bounded millisecond samples without claiming unsupported tails."
  [samples]
  (when-not (and (vector? samples) (seq samples)
                 (every? nonnegative-number? samples))
    (fail! "latency samples must be a non-empty finite nonnegative vector" {}))
  (let [ordered (vec (sort samples))
        n (count ordered)
        supported (into {} (map (fn [[name minimum]] [name (<= minimum n)]))
                        minimum-percentile-samples)]
    (cond-> {:count n :min-ms (first ordered) :max-ms (peek ordered)
             :mean-ms (/ (reduce + 0.0 ordered) n)
             :percentile-support supported}
      (:p50 supported) (assoc :p50-ms (quantile ordered 0.50))
      (:p95 supported) (assoc :p95-ms (quantile ordered 0.95))
      (:p99 supported) (assoc :p99-ms (quantile ordered 0.99)))))

(defn- valid-latency-summary? [summary]
  (and (map? summary)
       (= #{:count :min-ms :max-ms :mean-ms :percentile-support}
          (apply disj (set (keys summary)) [:p50-ms :p95-ms :p99-ms]))
       (integer? (:count summary)) (pos? (:count summary))
       (every? nonnegative-number? (map summary [:min-ms :max-ms :mean-ms]))
       (<= (:min-ms summary) (:mean-ms summary) (:max-ms summary))
       (= (into {} (map (fn [[name minimum]]
                          [name (<= minimum (:count summary))]))
                minimum-percentile-samples)
          (:percentile-support summary))
       (every? (fn [[percentile supported?]]
                 (= supported?
                    (contains? summary (keyword (str (name percentile) "-ms")))))
               (:percentile-support summary))
       (every? nonnegative-number? (keep summary [:p50-ms :p95-ms :p99-ms]))))

(def coverage-keys
  #{:valid :present-empty :absent :invalid :historical-untyped-fallback
    :historical-untyped-unavailable :total})

(defn- valid-coverage? [coverage row-count]
  (and (map? coverage) (= coverage-keys (set (keys coverage)))
       (every? #(and (integer? %) (not (neg? %))) (vals coverage))
       (= row-count (:total coverage))
       (= (:total coverage)
          (reduce + 0 (map coverage
                           [:valid :present-empty :absent :invalid
                            :historical-untyped-fallback
                            :historical-untyped-unavailable])))))

(defn- valid-aggregate? [aggregate]
  (and (= #{:count :min :max :avg} (set (keys aggregate)))
       (integer? (:count aggregate)) (pos? (:count aggregate))
       (integer? (:min aggregate)) (integer? (:max aggregate))
       (<= (:min aggregate) (:max aggregate)) (finite-number? (:avg aggregate))))

(defn- valid-query? [query]
  (and (= #{:operation :first-after-reopen-ms :warm-latency} (set (keys query)))
       (contains? operations (:operation query))
       (nonnegative-number? (:first-after-reopen-ms query))
       (valid-latency-summary? (:warm-latency query))))

(defn- valid-storage? [storage row-count]
  (and (= #{:method :table :optimized-final? :part-count :rows
            :bytes-on-disk :compressed-bytes :uncompressed-bytes}
          (set (keys storage)))
       (= :system-parts-active (:method storage)) (= "otel_traces" (:table storage))
       (true? (:optimized-final? storage))
       (every? #(and (integer? %) (not (neg? %)))
               (map storage [:part-count :rows :bytes-on-disk
                             :compressed-bytes :uncompressed-bytes]))
       (= row-count (:rows storage)) (pos? (:part-count storage))
       (pos? (:bytes-on-disk storage))))

(defn- valid-correctness? [correctness mode row-count missing-every]
  (let [missing-count (quot (+ row-count (dec missing-every)) missing-every)
        present-count (- row-count missing-count)
        expected-coverage
        (if (= :typed mode)
          {:valid present-count :present-empty 0 :absent missing-count :invalid 0
           :historical-untyped-fallback 0 :historical-untyped-unavailable 0
           :total row-count}
          {:valid 0 :present-empty 0 :absent missing-count :invalid 0
           :historical-untyped-fallback present-count
           :historical-untyped-unavailable 0 :total row-count})]
  (and (= #{:schema-binding-confirmed? :row-count-confirmed? :present-count
            :absent-count :int64-match-count :boolean-match-count
            :int64-filter-coverage :boolean-filter-coverage
            :int64-aggregate-coverage :int64-aggregate}
          (set (keys correctness)))
       (true? (:schema-binding-confirmed? correctness))
       (true? (:row-count-confirmed? correctness))
       (every? #(and (integer? %) (not (neg? %)))
               (map correctness [:present-count :absent-count
                                 :int64-match-count :boolean-match-count]))
       (= present-count (:present-count correctness))
       (= missing-count (:absent-count correctness))
       (<= (:int64-match-count correctness) present-count)
       (<= (:boolean-match-count correctness) present-count)
       (every? #(valid-coverage? % row-count)
               (map correctness [:int64-filter-coverage :boolean-filter-coverage
                                 :int64-aggregate-coverage]))
       (= [expected-coverage expected-coverage expected-coverage]
          (mapv correctness [:int64-filter-coverage :boolean-filter-coverage
                             :int64-aggregate-coverage]))
       (valid-aggregate? (:int64-aggregate correctness))
       (= present-count (get-in correctness [:int64-aggregate :count])))))

(defn- valid-case? [case configuration]
  (let [{:keys [mode row-count int64-cardinality distribution phases queries
                storage correctness]} case
        expected-batches
        (quot (+ row-count (dec (:batch-size configuration)))
              (:batch-size configuration))]
    (and (= #{:mode :row-count :int64-cardinality :distribution :phases
              :queries :storage :correctness} (set (keys case)))
         (contains? modes mode)
         (every? #(and (integer? %) (pos? %)) [row-count int64-cardinality])
         (= #{:missing-every} (set (keys distribution)))
         (pos-int? (:missing-every distribution))
         (= (:missing-every configuration) (:missing-every distribution))
         (= #{:setup :ingest :reopen} (set (keys phases)))
         (every? nonnegative-number?
                 [(get-in phases [:setup :elapsed-ms])
                  (get-in phases [:ingest :elapsed-ms])
                  (get-in phases [:reopen :elapsed-ms])])
         (= expected-batches (get-in phases [:ingest :batches]))
         (nonnegative-number? (get-in phases [:ingest :rows-per-second]))
         (valid-latency-summary? (get-in phases [:ingest :batch-latency]))
         (= expected-batches
            (get-in phases [:ingest :batch-latency :count]))
         (vector? queries) (= operations (set (map :operation queries)))
         (= (count operations) (count queries)) (every? valid-query? queries)
         (every? #(= (:query-samples configuration)
                     (get-in % [:warm-latency :count])) queries)
         (valid-storage? storage row-count)
         (valid-correctness? correctness mode row-count
                             (:missing-every distribution)))))

(defn compare-results!
  "Build bounded storage comparisons and reject typed/fallback result drift."
  [case-results]
  (mapv
   (fn [[[row-count cardinality] results]]
     (let [by-mode (into {} (map (juxt #(get-in % [:report :mode]) identity))
                         results)
           typed (:typed by-mode) fallback (:string-fallback by-mode)
           typed-bytes (get-in typed [:report :storage :bytes-on-disk])
           fallback-bytes (get-in fallback [:report :storage :bytes-on-disk])]
       (when-not (= modes (set (keys by-mode)))
         (fail! "comparison requires exactly one result per mode" {}))
       (when-not (= (:oracle typed) (:oracle fallback))
         (fail! "typed and string fallback query results differ" {}))
       (when-not (and (pos-int? typed-bytes) (pos-int? fallback-bytes))
         (fail! "comparison requires positive system.parts byte totals" {}))
       {:row-count row-count :int64-cardinality cardinality
        :method :system-parts-active :typed-bytes typed-bytes
        :string-fallback-bytes fallback-bytes
        :typed-to-fallback-ratio (/ (double typed-bytes) fallback-bytes)
        :results-equal? true}))
   (sort-by first
            (group-by (juxt #(get-in % [:report :row-count])
                            #(get-in % [:report :int64-cardinality])) case-results))))

(defn- valid-configuration? [configuration]
  (and (= #{:row-counts :cardinalities :batch-size :missing-every
            :query-warmups :query-samples :result-limit}
          (set (keys configuration)))
       (vector? (:row-counts configuration)) (seq (:row-counts configuration))
       (vector? (:cardinalities configuration)) (seq (:cardinalities configuration))
       (every? pos-int? (concat (:row-counts configuration)
                                (:cardinalities configuration)
                                [(:batch-size configuration)
                                 (:missing-every configuration)
                                 (:query-warmups configuration)
                                 (:query-samples configuration)
                                 (:result-limit configuration)]))))

(defn- valid-provenance? [provenance]
  (and (= #{:source-sha :source-sha-source :source-worktree-state
            :runtime :jolt-version :clojure-version :scheme-version
            :machine-type :os-name
            :os-arch :native-chdb-version :declared-chdb-version
            :dependency-pins :transport :cache-states :cold-cache-claimed?}
          (set (keys provenance)))
       (sha? (:source-sha provenance))
       (= :clean-worktree-runner (:source-sha-source provenance))
       (= :clean (:source-worktree-state provenance))
       (= :jolt (:runtime provenance))
       (every? nonblank-string?
               (map provenance [:jolt-version :clojure-version :scheme-version
                                :machine-type :os-name :os-arch
                                :native-chdb-version :declared-chdb-version]))
       (= dependency-pins (:dependency-pins provenance))
       (= :otlp-http-json (:transport provenance))
       (= [:first-after-reopen :warmed] (:cache-states provenance))
       (false? (:cold-cache-claimed? provenance))))

(defn- valid-payload? [configuration mode-order cases comparison]
  (let [expected-pairs (set (for [rows (:row-counts configuration)
                                  cardinality (:cardinalities configuration)]
                              [rows cardinality]))
        expected-case-order
        (vec (for [rows (:row-counts configuration)
                   cardinality (:cardinalities configuration)
                   mode mode-order]
               [rows cardinality mode]))
        actual-case-order (mapv (juxt :row-count :int64-cardinality :mode) cases)
        grouped (group-by (juxt :row-count :int64-cardinality) cases)]
    (and (= modes (set mode-order)) (= 2 (count mode-order))
         (vector? cases) (= expected-case-order actual-case-order)
         (every? #(valid-case? % configuration) cases)
         (= expected-pairs (set (keys grouped)))
         (every? #(and (= 2 (count %)) (= modes (set (map :mode %))))
                 (vals grouped))
         (vector? comparison)
         (= expected-pairs
            (set (map (juxt :row-count :int64-cardinality) comparison)))
         (every?
          #(and (= #{:row-count :int64-cardinality :method :typed-bytes
                      :string-fallback-bytes :typed-to-fallback-ratio
                      :results-equal?} (set (keys %)))
                (= :system-parts-active (:method %))
                (every? pos-int? (map % [:typed-bytes :string-fallback-bytes]))
                (nonnegative-number? (:typed-to-fallback-ratio %))
                (true? (:results-equal? %))
                (let [pair (get grouped [(:row-count %) (:int64-cardinality %)])
                      by-mode (into {} (map (juxt :mode identity)) pair)
                      typed-bytes (get-in by-mode [:typed :storage :bytes-on-disk])
                      fallback-bytes
                      (get-in by-mode [:string-fallback :storage :bytes-on-disk])]
                  (and (= typed-bytes (:typed-bytes %))
                       (= fallback-bytes (:string-fallback-bytes %))
                       (= (/ (double typed-bytes) fallback-bytes)
                          (:typed-to-fallback-ratio %)))))
          comparison))))

(defn validate-shard!
  "Reject an incomplete or dishonestly ordered single-process repetition."
  [shard]
  (let [{:keys [schema benchmark qualification configuration provenance
                repetition cases comparison]} shard
        {:keys [index count mode-order]} repetition]
    (when-not
     (and (= #{:schema :benchmark :qualification :configuration :provenance
               :repetition :cases :comparison} (set (keys shard)))
          (= shard-schema-version schema) (= :typed-query-storage benchmark)
          (contains? #{:smoke :representative} qualification)
          (valid-configuration? configuration) (valid-provenance? provenance)
          (= #{:index :count :mode-order} (set (keys repetition)))
          (valid-repetition-count? count)
          (integer? index) (<= 0 index) (< index count)
          (= (mode-order-for index) mode-order)
          (valid-payload? configuration mode-order cases comparison))
      (fail! "typed query/storage benchmark repetition is invalid" {}))
    shard))

(declare validate-report!)

(defn combine-shards!
  "Validate complete fresh process repetitions and construct one bounded report."
  [shards]
  (let [shards (mapv validate-shard! shards)
        first-shard (first shards)
        repetition-count (get-in first-shard [:repetition :count])
        common-keys [:benchmark :qualification :configuration :provenance]
        indexes (mapv #(get-in % [:repetition :index]) shards)]
    (when-not
     (and first-shard
          (= repetition-count (count shards))
          (= (vec (range repetition-count)) indexes)
          (every? #(= repetition-count (get-in % [:repetition :count])) shards)
          (every? #(= (select-keys first-shard common-keys)
                       (select-keys % common-keys)) shards))
      (fail! "benchmark repetitions are missing, duplicated, reordered, or stale" {}))
    (validate-report!
     {:schema schema-version
      :benchmark (:benchmark first-shard)
      :qualification (:qualification first-shard)
      :configuration (:configuration first-shard)
      :provenance (:provenance first-shard)
      :execution
      {:process-repetitions repetition-count
       :counterbalanced? (> repetition-count 1)
       :mode-orders
       (mapv (fn [shard]
               {:repetition-index (get-in shard [:repetition :index])
                :mode-order (get-in shard [:repetition :mode-order])}) shards)}
      :runs
      (mapv (fn [shard]
              {:repetition-index (get-in shard [:repetition :index])
               :mode-order (get-in shard [:repetition :mode-order])
               :cases (:cases shard) :comparison (:comparison shard)}) shards)})))

(defn validate-report!
  "Reject incomplete, biased, stale, tail-overclaiming, or non-conserving reports."
  [report]
  (let [{:keys [schema benchmark qualification configuration provenance
                execution runs]} report
        repetition-count (:process-repetitions execution)
        expected-orders
        (when (valid-repetition-count? repetition-count)
          (mapv (fn [index]
                  {:repetition-index index :mode-order (mode-order-for index)})
                (range repetition-count)))]
    (when-not
     (and (= #{:schema :benchmark :qualification :configuration :provenance
               :execution :runs} (set (keys report)))
          (= schema-version schema) (= :typed-query-storage benchmark)
          (contains? #{:smoke :representative} qualification)
          (valid-configuration? configuration) (valid-provenance? provenance)
          (= #{:process-repetitions :counterbalanced? :mode-orders}
             (set (keys execution)))
          (valid-repetition-count? repetition-count)
          (= (> repetition-count 1) (:counterbalanced? execution))
          (= expected-orders (:mode-orders execution))
          (vector? runs) (= repetition-count (count runs))
          (= expected-orders
             (mapv #(select-keys % [:repetition-index :mode-order]) runs))
          (every?
           (fn [run]
             (and (= #{:repetition-index :mode-order :cases :comparison}
                     (set (keys run)))
                  (valid-payload? configuration (:mode-order run)
                                  (:cases run) (:comparison run))))
           runs))
      (fail! "typed query/storage benchmark report is invalid" {}))
    report))

(defn write-artifact! [path artifact validator]
  (let [output (java.io.File. path)]
    (when-let [parent (.getParentFile output)]
      (.mkdirs parent))
    (spit output (str (pr-str artifact) "\n"))
    (let [read-back (edn/read-string (slurp output))]
      (validator read-back)
      (when-not (= artifact read-back)
        (fail! "benchmark artifact changed on readback" {}))
      (when-not (< (.length output) (* 1024 1024))
        (fail! "benchmark artifact exceeded its 1 MiB bound" {})))
    artifact))

(defn -main [& arguments]
  (when-not (and (<= 3 (count arguments)) (= "--output" (first arguments)))
    (fail! "combine requires --output followed by one or more shard paths" {}))
  (let [output (second arguments)
        shards (mapv #(edn/read-string (slurp %)) (drop 2 arguments))]
    (write-artifact! output (combine-shards! shards) validate-report!)
    (println "typed query/storage benchmark report complete")
    (println "process repetitions:" (count shards) "artifact:" output)))
