(ns oscope.reader-integration-test
  "Optional independent-reader proof using an installed clickhouse-local."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.process :as process]
            [oscope.live :as live]))

(def clickhouse-local "/usr/bin/clickhouse-local")
(def start-unix-nano 1700000000000000000)
(def end-unix-nano (+ start-unix-nano 10))
(def max-export-bytes (* 4 1024 1024))

(defn- run-reader! [path format query]
  ;; Every argument is passed directly to ProcessBuilder. No command string,
  ;; shell, expansion, redirection, or SQL interpolation is involved.
  (let [child (process/process
               [clickhouse-local
                "--file" (str path)
                "--input-format" format
                "--query" query]
               {:out :string :err :string})
        result (deref child 30000 ::timeout)]
    (when (= ::timeout result)
      (try (process/destroy-tree child) (catch Throwable _ nil))
      (throw (ex-info "clickhouse-local reader timed out"
                      {:oscope.reader/error true :format format})))
    (when-not (zero? (:exit result))
      (throw (ex-info "clickhouse-local could not parse oscope export"
                      {:oscope.reader/error true :format format
                       :exit (:exit result) :stderr (str (:err result))})))
    (str/trim (str (:out result)))))

(defn- export! [source format max-rows]
  ((:export-command source) [:independent-reader format max-rows]
   {:signal :spans :metric-kind nil
    :start-unix-nano start-unix-nano
    :end-unix-nano end-unix-nano
    :format format :max-rows max-rows :max-bytes max-export-bytes}))

(defn- insert-span! [connection timestamp trace-id]
  (jdbc/execute!
   connection
   ["INSERT INTO otel_traces
       (Timestamp, TraceId, SpanId, ServiceName, SpanName)
       VALUES (fromUnixTimestamp64Nano(?), ?, ?, 'reader-test', 'reader.span')"
    timestamp trace-id (str "span-" trace-id)]))

(defn- delete-owned! [paths]
  (let [failures (atom [])]
    (doseq [path paths]
      (try
        (java.nio.file.Files/deleteIfExists path)
        (catch Throwable error
          (swap! failures conj [(str path) (ex-message error)]))))
    (when (seq @failures)
      (throw (ex-info "could not clean independent-reader temporary paths"
                      {:oscope.reader/error true :failures @failures})))))

(deftest clickhouse-local-reads-boundaries-and-truncated-exports
  (let [directory (java.nio.file.Files/createTempDirectory
                   "oscope-reader-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        arrow-path (.resolve directory "boundary.arrow")
        parquet-path (.resolve directory "truncated.parquet")
        source* (atom nil)]
    (try
      (let [source (live/open! {:db-spec "chdb::memory:"
                                :now-fn (constantly end-unix-nano)})
            _ (reset! source* source)
            connection (:connection source)]
        ;; Only lower, middle-a, and middle-b are inside [start,end). The
        ;; before and exact-upper rows pin both comparisons independently.
        (insert-span! connection (dec start-unix-nano) "before")
        (insert-span! connection start-unix-nano "lower")
        (insert-span! connection (inc start-unix-nano) "middle-a")
        (insert-span! connection (+ start-unix-nano 2) "middle-b")
        (insert-span! connection end-unix-nano "upper")
        (let [arrow (export! source :arrow 10)
              parquet (export! source :parquet 2)]
          (java.nio.file.Files/write arrow-path (:bytes arrow)
                                     (make-array java.nio.file.OpenOption 0))
          (java.nio.file.Files/write parquet-path (:bytes parquet)
                                     (make-array java.nio.file.OpenOption 0))
          (is (= "3\t1\t0\t0"
                 (run-reader!
                  arrow-path "Arrow"
                  (str "SELECT count(), countIf(TraceId='lower'), "
                       "countIf(TraceId='upper'), countIf(TraceId='before') "
                       "FROM table FORMAT TabSeparated"))))
          (is (= "2\t1\t0"
                 (run-reader!
                  parquet-path "Parquet"
                  (str "SELECT count(), countIf(TraceId='lower'), "
                       "countIf(TraceId='middle-b') "
                       "FROM table FORMAT TabSeparated"))))))
      (finally
        (try
          ;; Delete exactly the three paths this test created. No recursive or
          ;; caller-selected path ever reaches cleanup. Attempt every deletion
          ;; even if one reports an error.
          (delete-owned! [arrow-path parquet-path directory])
          (finally
            (when-let [source @source*] (live/close! source))))))))
