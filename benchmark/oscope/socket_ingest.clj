(ns oscope.socket-ingest
  "Bounded local socket-ingestion screen; synthetic, not a population-tail gate."
  (:require [db.jdbc] [jdbc.core :as jdbc]
            [clojure.data.json :as json] [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str] [jolt.host :as host]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local]
            [jdbc.chdb.json-each-row :as legacy]
            [jdbc.chdb.native :as native]
            [jdbc.chdb.owned-statement :as owned]
            [otel.exporter.chdb :as exporter]
            [oscope.config :as config] [oscope.durable-config-runtime :as runtime]
            [oscope.otlp :as otlp] [oscope.server :as server]
            [teensyp.client :as client]))

(def ^:dynamic *request-document-read?* false)

(defn ensure! [ok label]
  (when-not ok (throw (ex-info "Socket ingest screen failed" {:label label}))))
(defn hex [width n] (format (str "%0" width "x") n))
(def resource {"service.name" "oscope-socket-bench" "region" "local"})
(defn attributes [index]
  (into {"index" index "active" (even? index)}
        (map (fn [i] [(str "extra-" i) (case (mod i 3) 0 "value" 1 false i)]) (range 12))))
(defn wire-attributes [values]
  (mapv (fn [[key value]]
          {"key" key "value" {(cond (boolean? value) "boolValue"
                                    (integer? value) "intValue"
                                    :else "stringValue")
                               (if (integer? value) (str value) value)}}) values))
(defn payload [n now]
  {"resourceSpans"
   [{"resource" {"attributes" (wire-attributes resource)}
     "scopeSpans"
     [{"scope" {"name" "oscope.socket-bench" "version" "1"}
       "spans"
       (mapv (fn [index]
               {"traceId" (hex 32 (inc index)) "spanId" (hex 16 (+ 100000 index))
                "parentSpanId" "" "traceState" "bench=socket"
                "name" "socket.ingest" "kind" 2
                "startTimeUnixNano" (str now) "endTimeUnixNano" (str (+ now 1000000))
                "attributes" (wire-attributes (attributes index))
                "status" {"code" 1}
                "events" [{"timeUnixNano" (str (+ now 100)) "name" "ready 🚀"
                           "attributes" (wire-attributes {"stage" "receive" "valid" false})}]
                "links" [{"traceId" "ffffffffffffffffffffffffffffffff"
                          "spanId" "eeeeeeeeeeeeeeee" "traceState" "bench=link"
                          "attributes" (wire-attributes {"peer" "local"})}]})
             (range n))}]}]})

(defn receive! [connection]
  (let [buffer (byte-array 4096)]
    (loop [chunks [] size 0]
      (if-let [n (client/receive-into! connection buffer 0 4096 {:timeout-ms 30000})]
        (do (ensure! (<= (+ size n) 65536) :bounded-response)
            (recur (conj chunks (String. buffer 0 n "UTF-8")) (+ size n)))
        (apply str chunks)))))
(defn post! [port body]
  (let [connection (client/connect "127.0.0.1" port {:connect-timeout-ms 5000})]
    (try
      (client/send-all! connection
        (.getBytes (str "POST /v1/traces HTTP/1.1\r\nHost: 127.0.0.1:" port
                        "\r\nContent-Type: application/json\r\nContent-Length: " (alength body)
                        "\r\nConnection: close\r\n\r\n") "UTF-8") {:timeout-ms 30000})
      (client/send-all! connection body {:timeout-ms 30000})
      (let [response (receive! connection)]
        (when-not (re-find #"^HTTP/1\.1 200 " response)
          ;; This driver emits only its fixed synthetic fixture. Keep a bounded
          ;; protocol error for diagnosis, never reinterpret a non-200 as ACK.
          (throw (ex-info "Socket ingest screen HTTP failure"
                          {:label :confirmed-http-success
                           :synthetic-response (subs response 0 (min 1024 (count response)))}))))
      (finally (client/close! connection)))))

(defn summary [samples]
  (let [ordered (vec (sort samples))
        quantile (fn [q] (nth ordered (max 0 (dec (long (Math/ceil (* q (count ordered))))))))]
    {:count (count ordered) :total-nanos (reduce + 0 ordered)
     :p50-nanos (quantile 0.50) :p90-nanos (quantile 0.90)
     :p95-nanos (quantile 0.95) :p99-nanos (quantile 0.99)
     :max-nanos (peek ordered)}))
(defn counters []
  ;; Separate coarse global snapshots; live heap alone is not allocation rate.
  {:gc-count (host/gc-count) :gc-bytes (host/gc-bytes)
   :live-heap (host/bytes-allocated) :gc-nanos (host/gc-real-nanos)
   :memory-bytes (host/current-memory-bytes)})
(defn allocated-estimate [before after]
  (- (+ (:gc-bytes after) (:live-heap after))
     (+ (:gc-bytes before) (:live-heap before))))
(defn process-counters []
  ;; Linux process CPU covers all native/handler threads, not just the client.
  (let [raw (slurp "/proc/self/stat")
        fields (str/split (subs raw (+ 2 (.lastIndexOf raw ")"))) #"\s+")
        status (slurp "/proc/self/status")
        kib (fn [name] (parse-long (second (re-find (re-pattern (str "(?m)^" name ":\\s+(\\d+) kB$")) status))))]
    {:user-ticks (parse-long (nth fields 11)) :system-ticks (parse-long (nth fields 12))
     :threads (parse-long (nth fields 17)) :rss-kib (kib "VmRSS") :highwater-kib (kib "VmHWM")}))

(def codec-options
  {:insert-format :json-compact-each-row :json-backend :native-guarded-byte-batch
   :owned-statement-output? true :datetime64-wire :raw-ticks
   :experimental-span-codec? true :experimental-span-payload-cache? true
   :experimental-span-flat-map-cache? true :experimental-span-nested-fragment-cache? true})
(def configured-options {:insert-format :json-compact-each-row :json-backend :configured
                         :datetime64-wire :raw-ticks})

(defn writer! [root mode n rounds]
  (ensure! (contains? #{"configured" "codec" "codec-native-reader" "codec-fused-finder" "codec-whole-reader"} mode) :known-mode)
  (ensure! (and (<= 1 n 10000) (<= 1 rounds 32)) :bounded-workload)
  (ensure! (not (.exists (java.io.File. (str root "/seal.edn")))) :fresh-output)
  (ensure! (not (.exists (java.io.File. (str root "/objects")))) :fresh-store)
  (native/ensure-loaded!)
  (ensure! (= "26.9.0" (native/chdb-version)) :native-version)
  (let [now (* (System/currentTimeMillis) 1000000)
        body (.getBytes (json/write-str (payload n now)) "UTF-8")
        _ (ensure! (<= (alength body) otlp/max-body-bytes) :current-receiver-body-limit)
        codec? (contains? #{"codec" "codec-native-reader" "codec-fused-finder" "codec-whole-reader"} mode)
        fused? (= mode "codec-fused-finder")
        finder-var (when fused? (ns-resolve 'clojure.data.json 'next-string-special))
        _ (when fused? (ensure! finder-var :explicit-finder-seam-available))
        fused-finder (when fused?
                       (require 'oscope.fused-json)
                       ((ns-resolve 'oscope.fused-json 'open-finder)))
        whole? (= mode "codec-whole-reader")
        reader? (or whole? (= mode "codec-native-reader"))
        reader-binding (when reader? (ns-resolve 'clojure.data.json
                                               (if whole? '*experimental-native-reader*
                                                 '*experimental-native-string-reader*)))
        _ (when reader? (ensure! reader-binding :explicit-reader-hook-available))
        string-reader (when reader?
                        (require 'clojure.data.json.jolt-native)
                        ((ns-resolve 'clojure.data.json.jolt-native
                                     (if whole? 'load-reader! 'load-string-reader!))))
        _ (when reader? (ensure! (ifn? string-reader) :explicit-reader-callable))
        codec-var (when codec? (require 'jolt.codec.experimental)
                    (ns-resolve 'jolt.codec.experimental 'encode-prefixed-records))
        codec-count (atom 0) stream-count (atom 0) reader-requests (atom 0)
        finder-requests (atom 0)
        active? (atom false) phases (atom {})
        observe (fn [label f]
                  (fn [& args]
                    (if-not @active? (apply f args)
                      (let [start (System/nanoTime) result (apply f args)]
                        (swap! phases update label (fnil conj []) (- (System/nanoTime) start))
                        result))))
        original-parse otlp/parse-json-body original-stream native/chdb-stream-insert-n
        ;; Bind inside the real handler invocation, not on the producer thread.
        ;; No per-token instrumentation is added to the timed reader loop.
        request-parser (fn [& args]
                         (binding [*request-document-read?* true]
                           (cond
                             reader? (do (swap! reader-requests inc)
                                         (with-bindings {reader-binding string-reader}
                                           (apply original-parse args)))
                             ;; Experiment only: one scalar Scheme procedure
                             ;; replaces the private finder during synchronous
                             ;; parsing. No per-token wrapper/counter is added.
                             fused? (do (swap! finder-requests inc)
                                        (with-redefs-fn {finder-var fused-finder}
                                          #(apply original-parse args)))
                             :else (apply original-parse args))))
        original-json-read json/read-str
        observed-json-read (observe :json-document-read original-json-read)
        original-codec (when codec-var @codec-var)
        overrides (cond-> {#'otlp/parse-json-body (observe :body-read-and-json-parse request-parser)
                           #'json/read-str (fn [& args]
                                            (apply (if *request-document-read?*
                                                     observed-json-read original-json-read) args))
                           #'durable/execute-and-flush! (observe :string-durable-api durable/execute-and-flush!)
                           #'durable/execute-owned-and-flush! (observe :owned-durable-api durable/execute-owned-and-flush!)
                           #'owned/precompute-wal (observe :wal-preparation owned/precompute-wal)
                           #'native/chdb-stream-insert-n
                           (observe :native-stream
                             (fn [& args] (swap! stream-count inc) (apply original-stream args)))
                           #'legacy/open-encoder (fn [& _] (throw (ex-info "Unexpected legacy JSON writer" {})))}
                    codec? (assoc codec-var
                             (observe :project-and-encode
                               (fn [& args] (swap! codec-count inc) (apply original-codec args)))))]
    (ensure! (.mkdir (java.io.File. (str root "/scratch"))) :fresh-scratch-parent)
    (spit (str root "/seal.edn") (pr-str {:n n :rounds rounds :warmups 1 :now now :mode mode}))
    (with-redefs-fn overrides
      (fn []
        (let [start (System/nanoTime)
              lifecycle (server/start!
                          (runtime/server-options
                            (config/resolve-config
                              [[:file {:version 2 :server {:port 0}
                                       :storage {:type :durable-local :root (str root "/objects")
                                                 :owner "socket-bench" :instance "writer"
                                                 :scratch-parent (str root "/scratch")
                                                 :lease-ttl-ms 300000 :heartbeat-interval-ms 60000
                                                 :owned-compact-stream? true
                                                 :export-options (if codec? codec-options configured-options)}}]]) {}))
              startup-nanos (- (System/nanoTime) start)]
          (try
            ;; Startup capability probes are not ingestion evidence.
            (reset! codec-count 0)
            (reset! stream-count 0)
            (reset! reader-requests 0)
            (reset! finder-requests 0)
            (post! (:port lifecycle) body)
            (System/gc)
            (reset! active? true)
            (let [os-before (process-counters) before (counters) window-start (System/nanoTime)
                  samples (mapv (fn [_] (let [start (System/nanoTime)]
                                         (post! (:port lifecycle) body)
                                         (- (System/nanoTime) start))) (range rounds))
                  window-nanos (- (System/nanoTime) window-start) after (counters) os-after (process-counters)
                  _ (reset! active? false)
                  latency (summary samples)
                  rows (* n rounds)
                  report {:mode mode :batch-size n :rounds rounds :warmups 1
                          :wire-bytes (alength body) :body-limit otlp/max-body-bytes
                          :startup-nanos startup-nanos :window-nanos window-nanos
                          :confirmed-rows rows :window-rows-per-second (/ (* rows 1e9) window-nanos)
                          :latency latency :samples-nanos samples
                          :p50-latency-equivalent-rows-per-second (/ (* n 1e9) (:p50-nanos latency))
                          :p99-latency-equivalent-rows-per-second (/ (* n 1e9) (:p99-nanos latency))
                          :counter-before before :counter-after after
                          :process-before os-before :process-after os-after
                          :os-ticks-per-second (some-> (System/getenv "BENCH_CLK_TCK") parse-long)
                          :exporter-source (str (io/resource "otel/exporter/chdb.clj"))
                          :json-source (str (io/resource "clojure/data/json.clj"))
                          :codec-source (when codec? (str (io/resource "jolt/codec/experimental.clj")))
                          :estimated-allocated-bytes-per-row (/ (double (allocated-estimate before after)) rows)
                          :phase-samples @phases :phase-summaries (into {} (map (fn [[k v]] [k (summary v)]) @phases))
                          :phase-overlap "json-document-read is inside body-read-and-json-parse; native-stream is inside Durable API; do not sum inclusive phases"
                          :codec-calls @codec-count :stream-calls @stream-count
                          :native-string-reader? (and reader? (not whole?))
                          :whole-reader? whole? :reader-requests @reader-requests
                          :fused-finder? fused? :finder-requests @finder-requests
                          :scope "instrumented sequential local sockets, pre-encoded client body; small sample not population-tail proof"}]
              (ensure! (= (if codec? (inc rounds) 0) @codec-count) :observed-codec-path)
              (ensure! (= (if codec? (inc rounds) 0) @stream-count) :observed-native-path)
              (ensure! (= rounds (count (:body-read-and-json-parse @phases))) :actual-request-parser)
              (when-not (= rounds (count (:json-document-read @phases)))
                (throw (ex-info "Document parser observation mismatch"
                                {:label :actual-document-parser
                                 :phase-counts (into {} (map (fn [[k v]] [k (count v)]) @phases))})))
              (ensure! (= (if reader? (inc rounds) 0) @reader-requests) :observed-reader-requests)
              (ensure! (= (if fused? (inc rounds) 0) @finder-requests) :observed-finder-requests)
              (spit (str root "/writer.edn") (pr-str report))
              (ensure! (= {:status :closed :phase :closed} (server/stop! lifecycle)) :writer-closed)
              (println :socket-writer-closed {:mode mode :rows rows :wire-bytes (alength body)}))
            (catch Throwable failure
              (when-let [error (exporter/last-error (:exporter lifecycle))]
                ;; Bounded diagnosis for this synthetic fixture only.
                (spit (str root "/export-failure.edn")
                      (pr-str {:type (:type (ex-data error))
                               :message (subs (str (ex-message error)) 0
                                              (min 2048 (count (str (ex-message error)))))
                               :data (ex-data error)})))
              (throw failure))
            (finally (reset! active? false) (server/stop! lifecycle))))))))

(def columns
  ["Timestamp" "TraceId" "SpanId" "ParentSpanId" "TraceState" "SpanName" "SpanKind"
   "ServiceName" "ResourceAttributes" "ScopeName" "ScopeVersion" "SpanAttributes"
   "Duration" "StatusCode" "StatusMessage" "Events.Timestamp" "Events.Name"
   "Events.Attributes" "Links.TraceId" "Links.SpanId" "Links.TraceState" "Links.Attributes"])
(defn expected-row [now index]
  (zipmap (map #(keyword (str "f" %)) (range (count columns)))
    [now (hex 32 (inc index)) (hex 16 (+ 100000 index)) "" "bench=socket"
     "socket.ingest" "Server" "oscope-socket-bench" resource "oscope.socket-bench" "1"
     (into {} (map (fn [[k v]] [k (str v)]) (attributes index))) 1000000 "Ok" ""
     [(+ now 100)] ["ready 🚀"] [{"stage" "receive" "valid" "false"}]
     ["ffffffffffffffffffffffffffffffff"] ["eeeeeeeeeeeeeeee"] ["bench=link"] [{"peer" "local"}]]))
(defn reader! [root]
  (let [{:keys [n rounds warmups now]} (edn/read-string (slurp (str root "/seal.edn")))
        _ (ensure! (and (<= 1 n 10000) (<= 1 rounds 32) (= warmups 1)) :bounded-reader)
        select (str "SELECT "
                    (str/join ", "
                      (map-indexed
                        (fn [i name]
                          (str (case name
                                 "Timestamp" "toUnixTimestamp64Nano(Timestamp)"
                                 "Events.Timestamp" "arrayMap(x -> toUnixTimestamp64Nano(x), `Events.Timestamp`)"
                                 (str "`" name "`")) " AS f" i)) columns))
                    " FROM otel_traces SETTINGS output_format_json_quote_64bit_integers=0")
        started (System/nanoTime)]
    (with-open [connection (jdbc/connection
                            (durable/snapshot-dbspec {:backend (local/local-backend (str root "/objects"))}))]
      (let [actual (vec (jdbc/fetch connection select))
            expected (into {} (map (fn [index] [(expected-row now index) (+ rounds warmups)]) (range n)))]
        (ensure! (= (* n (+ rounds warmups)) (count actual)) :recovered-row-count)
        (when-not (= expected (frequencies actual))
          (spit (str root "/reader-mismatch.edn") (pr-str {:expected (expected-row now 0) :actual (first actual)})))
        (ensure! (= expected (frequencies actual)) :recovered-core-fields-and-multiplicity)
        (spit (str root "/reader.edn") (pr-str {:rows (count actual) :core-columns (count columns)
                                                :independent-reader true :nanos (- (System/nanoTime) started)}))))
    (println :socket-reader-qualified)))
(defn -main [phase root & [mode n rounds]]
  (case phase
    "writer" (writer! root mode (parse-long n) (parse-long rounds))
    "reader" (reader! root)
    (throw (ex-info "Unknown socket screen phase" {}))))
