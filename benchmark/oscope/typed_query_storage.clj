(ns oscope.typed-query-storage
  "Reproducible local chDB qualification for typed span promotion and queries."
  (:require [clojure.data.json :as json]
            [db.jdbc]
            [jdbc.chdb.native :as chdb-native]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [oscope.server :as server]
            [oscope.typed-query-storage-report :as report]
            [oscope.typed-schema-runtime :as typed-schema-runtime]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [teensyp.client :as client])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private selector
  {:dataset-id "oscope-benchmark" :application-id "typed-query-storage"
   :lineage "typed-query-storage-v1" :version 1})
(def ^:private int64-key "benchmark.int64")
(def ^:private boolean-key "benchmark.boolean")

(def profiles
  {:smoke {:row-counts [32] :cardinalities [4] :batch-size 16
           :missing-every 5 :query-warmups 1 :query-samples 5 :result-limit 100}
   :representative
   {:row-counts [1000 10000 50000] :cardinalities [2 32 256]
    :batch-size 256 :missing-every 5 :query-warmups 10 :query-samples 100
    :result-limit 100}})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :oscope.typed-query-storage/error true))))

(defn- ensure! [condition message data]
  (when-not condition (fail! message data)))

(defn- elapsed-ms [start]
  (/ (double (- (System/nanoTime) start)) 1000000.0))

(defn- timed [f]
  (let [start (System/nanoTime) value (f)]
    {:elapsed-ms (elapsed-ms start) :value value}))

(defn- approved-manifest []
  (manifest/compile-manifest
   (assoc selector :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :runtime-reviewed
            :source "benchmark/typed-query-storage"
            :entries
            [{:signal :spans :table "otel_traces" :location :span-attributes
              :key int64-key :type :int64}
             {:signal :spans :table "otel_traces" :location :span-attributes
              :key boolean-key :type :boolean}]}])))

(defn- typed-schema-options [database-path startup-mode]
  (typed-schema-runtime/standalone-typed-schema
   {:type :local-path :path database-path}
   (case startup-mode
     :install {:mode :install :registry selector
               :approved-manifest (approved-manifest)}
     :acquire {:mode :acquire :registry selector})))

(defn- server-options [mode database-path startup-mode]
  (cond-> {:port 0 :db-spec (str "chdb:" database-path)}
    (= :typed mode)
    (assoc :typed-schema (typed-schema-options database-path startup-mode))))

(defn- copy-range [source start end]
  (let [result (byte-array (- end start))]
    (System/arraycopy source start result 0 (- end start)) result))

(defn- receive-all! [connection]
  (let [scratch (byte-array 8192)]
    (loop [chunks [] total 0]
      (if-let [length (client/receive-into! connection scratch 0 (alength scratch)
                                             {:timeout-ms 10000})]
        (recur (conj chunks (copy-range scratch 0 length)) (+ total length))
        (let [result (byte-array total)]
          (loop [remaining chunks offset 0]
            (when-let [chunk (first remaining)]
              (System/arraycopy chunk 0 result offset (alength chunk))
              (recur (next remaining) (+ offset (alength chunk)))))
          result)))))

(defn- header-end [bytes]
  (loop [index 0]
    (when (<= (+ index 4) (alength bytes))
      (if (= [13 10 13 10]
             (mapv #(bit-and 255 (int (aget bytes %))) (range index (+ index 4))))
        index
        (recur (inc index))))))

(defn- post-traces! [port payload]
  (let [connection (client/connect "127.0.0.1" port {:connect-timeout-ms 10000})
        encoded (.getBytes (json/write-str payload) "UTF-8")]
    (try
      (client/send-all!
       connection
       (.getBytes
        (str "POST /v1/traces HTTP/1.1\r\nHost: 127.0.0.1:" port "\r\n"
             "Content-Type: application/json\r\nContent-Length: "
             (alength encoded) "\r\nConnection: close\r\n\r\n") "UTF-8")
       {:timeout-ms 10000})
      (client/send-all! connection encoded {:timeout-ms 10000})
      (let [raw (receive-all! connection) end (header-end raw)]
        (ensure! (integer? end) "OTLP response had no complete header" {})
        (let [head (String. (copy-range raw 0 end) "UTF-8")
              [_ status] (re-find #"HTTP/1\.1 ([0-9]{3})" head)]
          (ensure! (= 200 (parse-long status))
                   "OTLP trace batch was not accepted" {})))
      (finally (client/close! connection)))))

(defn- attribute [key kind value]
  {"key" key "value" {kind value}})

(defn- present? [index missing-every]
  (not (zero? (mod index missing-every))))

(defn- trace-span [now index cardinality missing-every]
  {"traceId" (format "%032x" (inc index))
   "spanId" (format "%016x" (inc index))
   "name" "typed-query-storage" "kind" 2
   "startTimeUnixNano" (str (+ now (* index 2)))
   "endTimeUnixNano" (str (+ now (* index 2) 1))
   "attributes"
   (if (present? index missing-every)
     [(attribute int64-key "intValue" (str (mod index cardinality)))
      (attribute boolean-key "boolValue" (even? index))]
     [])})

(defn- trace-request [now start end cardinality missing-every]
  {"resourceSpans"
   [{"resource" {"attributes"
                  [(attribute "service.name" "stringValue" "oscope-benchmark")]}
     "scopeSpans"
     [{"scope" {"name" "oscope.typed-query-storage"}
       "spans" (mapv #(trace-span now % cardinality missing-every)
                     (range start end))}]}]})

(defn- batches [row-count batch-size]
  (mapv (fn [start] [start (min row-count (+ start batch-size))])
        (range 0 row-count batch-size)))

(defn- ingest! [lifecycle configuration row-count cardinality now]
  (let [latencies
        (mapv (fn [[start end]]
                (:elapsed-ms
                 (timed #(post-traces!
                          (:port lifecycle)
                          (trace-request now start end cardinality
                                         (:missing-every configuration))))))
              (batches row-count (:batch-size configuration)))
        elapsed (reduce + 0.0 latencies)]
    {:elapsed-ms elapsed :batches (count latencies)
     :rows-per-second (if (zero? elapsed) 0.0 (/ (* row-count 1000.0) elapsed))
     :batch-latency (report/latency-summary latencies)}))

(defn- stop! [lifecycle]
  (when lifecycle
    (ensure! (= :closed (:status (server/stop! lifecycle)))
             "standalone benchmark lifecycle did not close" {})))

(defn- binding-by-type [source type]
  (first (filter #(= type (:attribute-type %)) (:typed-span-fields source))))

(defn- coverage-map [screen]
  (into {} (map (juxt :status :count)) (:coverage screen)))

(defn- typed-filter [source field-type value result-limit]
  (let [binding (binding-by-type source field-type)
        screen ((:load-command source) [:benchmark :filter field-type]
                {:mode :typed-span-filter :schema-binding binding
                 :operator :eq :value value :window :1h :limit result-limit})]
    {:ids (mapv (juxt :trace-id :span-id) (get-in screen [:table :rows]))
     :coverage (coverage-map screen)}))

(defn- typed-aggregate [source result-limit]
  (let [binding (binding-by-type source :int64)
        screen ((:load-command source) [:benchmark :aggregate :int64]
                {:mode :typed-span-int64-aggregate :schema-binding binding
                 :predicate {:gte 0} :group-by []
                 :aggregates [:count :min :max :avg]
                 :window :1h :limit result-limit})
        row (first (get-in screen [:table :rows]))]
    {:aggregate (select-keys row [:count :min :max :avg])
     :coverage (coverage-map screen)}))

(def ^:private fallback-filter-sql
  (str "SELECT TraceId AS traceid, SpanId AS spanid FROM otel_traces "
       "WHERE toUnixTimestamp64Nano(Timestamp) >= ? "
       "AND toUnixTimestamp64Nano(Timestamp) < ? "
       "AND mapContains(SpanAttributes, ?) AND SpanAttributes[?] = ? "
       "ORDER BY Timestamp DESC, TraceId ASC, SpanId ASC LIMIT ? "
       "SETTINGS max_threads = 1"))

(def ^:private fallback-coverage-sql
  (str "SELECT countIf(mapContains(SpanAttributes, ?)) AS present, "
       "count() AS total FROM otel_traces "
       "WHERE toUnixTimestamp64Nano(Timestamp) >= ? "
       "AND toUnixTimestamp64Nano(Timestamp) < ? SETTINGS max_threads = 1"))

(def ^:private fallback-aggregate-sql
  (str "SELECT count() AS count, min(toInt64(SpanAttributes[?])) AS min, "
       "max(toInt64(SpanAttributes[?])) AS max, "
       "avg(toInt64(SpanAttributes[?])) AS avg FROM otel_traces "
       "WHERE toUnixTimestamp64Nano(Timestamp) >= ? "
       "AND toUnixTimestamp64Nano(Timestamp) < ? "
       "AND mapContains(SpanAttributes, ?) SETTINGS max_threads = 1"))

(defn- fallback-coverage [connection now key]
  (let [start (- now (* 60 60 1000000000)) end (+ now (* 60 60 1000000000))
        {:keys [present total]}
        (first (jdbc/fetch connection [fallback-coverage-sql key start end]
                           {:max-rows 1}))]
    {:valid 0 :present-empty 0 :absent (- total present) :invalid 0
     :historical-untyped-fallback present :historical-untyped-unavailable 0
     :total total}))

(defn- fallback-filter [connection now key value result-limit]
  (let [start (- now (* 60 60 1000000000)) end (+ now (* 60 60 1000000000))
        rows (jdbc/fetch connection
                         [fallback-filter-sql start end key key (str value)
                          result-limit]
                         {:max-rows result-limit})]
    {:ids (mapv (juxt :traceid :spanid) rows)
     :coverage (fallback-coverage connection now key)}))

(defn- fallback-aggregate [connection now]
  (let [start (- now (* 60 60 1000000000)) end (+ now (* 60 60 1000000000))
        row (first (jdbc/fetch connection
                               [fallback-aggregate-sql int64-key int64-key
                                int64-key start end int64-key]
                               {:max-rows 1}))]
    {:aggregate {:count (:count row) :min (:min row) :max (:max row)
                 :avg (double (:avg row))}
     :coverage (fallback-coverage connection now int64-key)}))

(defn- query-fns [mode lifecycle int64-value result-limit now]
  (if (= :typed mode)
    {:int64-filter #(typed-filter (:source lifecycle) :int64 int64-value result-limit)
     :boolean-filter #(typed-filter (:source lifecycle) :boolean true result-limit)
     :int64-aggregate #(typed-aggregate (:source lifecycle) result-limit)}
    {:int64-filter #(fallback-filter (:connection lifecycle) now int64-key
                                     int64-value result-limit)
     :boolean-filter #(fallback-filter (:connection lifecycle) now boolean-key
                                       true result-limit)
     :int64-aggregate #(fallback-aggregate (:connection lifecycle) now)}))

(defn- measured-query [operation query warmups samples]
  (let [first-result (timed query)]
    (dotimes [_ warmups] (query))
    (let [measured (mapv (fn [_] (timed query)) (range samples))]
      {:operation operation :first-after-reopen-ms (:elapsed-ms first-result)
       :warm-latency (report/latency-summary (mapv :elapsed-ms measured))
       :oracle (:value first-result)
       :measured-values (mapv :value measured)})))

(defn- optimize-and-storage! [connection row-count]
  (jdbc/execute! connection "OPTIMIZE TABLE otel_traces FINAL")
  (let [row (first
             (jdbc/fetch
              connection
              (str "SELECT count() AS partcount, toUInt64(sum(rows)) AS rows, "
                   "toUInt64(sum(bytes_on_disk)) AS bytesondisk, "
                   "toUInt64(sum(data_compressed_bytes)) AS compressedbytes, "
                   "toUInt64(sum(data_uncompressed_bytes)) AS uncompressedbytes "
                   "FROM system.parts WHERE active AND database = currentDatabase() "
                   "AND table = 'otel_traces'") {:max-rows 1}))
        storage {:method :system-parts-active :table "otel_traces"
                 :optimized-final? true :part-count (:partcount row)
                 :rows (:rows row) :bytes-on-disk (:bytesondisk row)
                 :compressed-bytes (:compressedbytes row)
                 :uncompressed-bytes (:uncompressedbytes row)}]
    (ensure! (= row-count (:rows storage))
             "system.parts did not account for every ingested row" {})
    (ensure! (pos? (:bytes-on-disk storage))
             "system.parts did not expose a comparable physical footprint" {})
    storage))

(defn- catalog-confirmed? [mode lifecycle]
  (if (= :typed mode)
    (let [source (:source lifecycle) fields (:typed-span-fields source)]
      (and (= (:typed-span-descriptors lifecycle)
              (:typed-span-descriptors source))
           (= #{[int64-key :int64] [boolean-key :boolean]}
              (set (map (juxt :attribute-key :attribute-type) fields)))))
    (and (nil? (:typed-span-descriptors lifecycle))
         (nil? (:typed-span-fields (:source lifecycle))))))

(defn- expected-fixture [row-count cardinality missing-every result-limit]
  (let [indexes (filter #(present? % missing-every) (range row-count))
        values (mapv #(mod % cardinality) indexes)
        int64-value (quot cardinality 2)]
    {:present-count (count indexes) :absent-count (- row-count (count indexes))
     :int64-value int64-value
     :int64-match-count (min result-limit (count (filter #(= int64-value %) values)))
     :boolean-match-count
     (min result-limit (count (filter even? indexes)))
     :aggregate {:count (count values) :min (reduce min values)
                 :max (reduce max values)
                 :avg (/ (double (reduce + 0 values)) (count values))}}))

(defn- ensure-stable! [sample]
  (ensure! (every? #(= (:oracle sample) %) (:measured-values sample))
           "query result changed across measured samples"
           {:operation (:operation sample)}))

(defn- expected-coverage [mode row-count present-count absent-count]
  (if (= :typed mode)
    {:valid present-count :present-empty 0 :absent absent-count :invalid 0
     :historical-untyped-fallback 0 :historical-untyped-unavailable 0
     :total row-count}
    {:valid 0 :present-empty 0 :absent absent-count :invalid 0
     :historical-untyped-fallback present-count
     :historical-untyped-unavailable 0 :total row-count}))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (Files/deleteIfExists (.toPath file))))

(defn- run-case [mode configuration row-count cardinality]
  (let [profile-root (.toFile
                      (Files/createTempDirectory "oscope-typed-query-storage-"
                                                 (make-array FileAttribute 0)))
        database-path (str (.resolve (.toPath profile-root) "telemetry"))
        now (* (System/currentTimeMillis) 1000000) lifecycle* (atom nil)]
    (try
      (let [setup (timed #(server/start! (server-options mode database-path :install)))
            lifecycle (:value setup) _ (reset! lifecycle* lifecycle)
            ingest (ingest! lifecycle configuration row-count cardinality now)
            storage (optimize-and-storage! (:connection lifecycle) row-count)
            _ (stop! lifecycle) _ (reset! lifecycle* nil)
            reopen (timed #(server/start!
                            (server-options mode database-path
                                            (if (= :typed mode) :acquire :install))))
            restarted (:value reopen) _ (reset! lifecycle* restarted)
            fixture (expected-fixture row-count cardinality
                                      (:missing-every configuration)
                                      (:result-limit configuration))
            queries (query-fns mode restarted (:int64-value fixture)
                               (:result-limit configuration) now)
            samples (mapv (fn [operation]
                            (measured-query operation (get queries operation)
                                            (:query-warmups configuration)
                                            (:query-samples configuration)))
                          [:int64-filter :boolean-filter :int64-aggregate])
            _ (doseq [sample samples] (ensure-stable! sample))
            by-operation (into {} (map (juxt :operation :oracle)) samples)
            int64-result (:int64-filter by-operation)
            boolean-result (:boolean-filter by-operation)
            aggregate-result (:int64-aggregate by-operation)
            expected-cov (expected-coverage mode row-count (:present-count fixture)
                                            (:absent-count fixture))
            row-count-observed
            (:count (first (jdbc/fetch (:connection restarted)
                                       "SELECT count() AS count FROM otel_traces"
                                       {:max-rows 1})))
            binding-confirmed? (catalog-confirmed? mode restarted)
            result
            {:mode mode :row-count row-count :int64-cardinality cardinality
             :distribution {:missing-every (:missing-every configuration)}
             :phases {:setup {:elapsed-ms (:elapsed-ms setup)} :ingest ingest
                      :reopen {:elapsed-ms (:elapsed-ms reopen)}}
             :queries (mapv #(dissoc % :oracle :measured-values) samples)
             :storage storage
             :correctness
             {:schema-binding-confirmed? (boolean binding-confirmed?)
              :row-count-confirmed? (= row-count row-count-observed)
              :present-count (:present-count fixture)
              :absent-count (:absent-count fixture)
              :int64-match-count (count (:ids int64-result))
              :boolean-match-count (count (:ids boolean-result))
              :int64-filter-coverage (:coverage int64-result)
              :boolean-filter-coverage (:coverage boolean-result)
              :int64-aggregate-coverage (:coverage aggregate-result)
              :int64-aggregate (:aggregate aggregate-result)}}]
        (ensure! binding-confirmed? "schema binding did not survive restart" {})
        (ensure! (= row-count row-count-observed) "reopened row count changed" {})
        (ensure! (= (:int64-match-count fixture) (count (:ids int64-result)))
                 "Int64 filter count did not match fixture" {})
        (ensure! (= (:boolean-match-count fixture) (count (:ids boolean-result)))
                 "Boolean filter count did not match fixture" {})
        (ensure! (= expected-cov (:coverage int64-result)
                                 (:coverage boolean-result)
                                 (:coverage aggregate-result))
                 "coverage did not match the known fixture distribution" {})
        (ensure! (= (:aggregate fixture) (:aggregate aggregate-result))
                 "Int64 aggregate did not match fixture" {})
        {:report result
         :oracle {:int64-filter-ids (:ids int64-result)
                  :boolean-filter-ids (:ids boolean-result)
                  :int64-aggregate (:aggregate aggregate-result)}})
      (finally (stop! @lifecycle*) (delete-tree! profile-root)))))

(defn- provenance [source-sha source-worktree-state]
  {:source-sha source-sha :source-sha-source :clean-worktree-runner
   :source-worktree-state source-worktree-state
   :runtime :jolt :jolt-version (System/getProperty "jolt.version")
   :clojure-version (clojure-version) :scheme-version (host/scheme-version)
   :machine-type (host/machine-type) :os-name (System/getProperty "os.name")
   :os-arch (System/getProperty "os.arch")
   :native-chdb-version (chdb-native/chdb-version)
   :declared-chdb-version chdb-native/version
   :dependency-pins report/dependency-pins :transport :otlp-http-json
   :cache-states [:first-after-reopen :warmed] :cold-cache-claimed? false})

(defn run! [qualification source-sha source-worktree-state repetition-index
            repetition-count output-path]
  (ensure! (and (string? source-sha)
                (boolean (re-matches #"[0-9a-f]{40}" source-sha)))
           "benchmark source SHA must be full lowercase hexadecimal" {})
  (ensure! (contains? #{:clean :dirty} source-worktree-state)
           "benchmark source worktree state must be clean or dirty" {})
  (ensure! (or (= :smoke qualification) (= :clean source-worktree-state))
           "representative benchmark requires a clean committed worktree" {})
  (ensure! (report/valid-repetition-count? repetition-count)
           "process repetition count must be one or an even integer up to ten" {})
  (ensure! (and (integer? repetition-index) (<= 0 repetition-index)
                (< repetition-index repetition-count))
           "process repetition index is outside the declared repetition set" {})
  (ensure! (and (string? output-path) (not (empty? output-path)))
           "benchmark repetition output path must be nonempty" {})
  (let [configuration (or (get profiles qualification)
                          (fail! "benchmark profile must be smoke or representative" {}))
        mode-order (report/mode-order-for repetition-index)
        results (vec (for [row-count (:row-counts configuration)
                           cardinality (:cardinalities configuration)
                           mode mode-order]
                       (run-case mode configuration row-count cardinality)))
        artifact {:schema report/shard-schema-version
                  :benchmark :typed-query-storage
                  :qualification qualification :configuration configuration
                  :provenance (provenance source-sha source-worktree-state)
                  :repetition {:index repetition-index :count repetition-count
                               :mode-order mode-order}
                  :cases (mapv :report results)
                  :comparison (report/compare-results! results)}]
    (report/write-artifact! output-path artifact report/validate-shard!)
    artifact))

(defn- parse-options [arguments]
  (when-not (and
             (= 8 (count arguments))
             (= ["--profile" "--repetition-index" "--repetition-count" "--output"]
                (mapv first (partition 2 arguments))))
    (fail! "use the typed query/storage benchmark runner script" {}))
  {:profile (keyword (second arguments))
   :repetition-index (parse-long (nth arguments 3))
   :repetition-count (parse-long (nth arguments 5))
   :output (nth arguments 7)
   :source-sha (System/getenv "OSCOPE_BENCHMARK_SOURCE_SHA")
   :source-state (keyword (or (System/getenv "OSCOPE_BENCHMARK_SOURCE_STATE")
                              "missing"))})

(defn -main [& arguments]
  (let [{:keys [profile source-sha source-state repetition-index repetition-count
                output]} (parse-options arguments)
        artifact (run! profile source-sha source-state repetition-index
                       repetition-count output)]
    (println "typed query/storage benchmark" (name profile) "repetition"
             (inc repetition-index) "of" repetition-count "complete")
    (println "mode order:" (pr-str (get-in artifact [:repetition :mode-order]))
             "artifact:" output)))
