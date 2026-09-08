(ns oscope.durable-integration-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]
            [oscope.raw-export.chdb :as raw-export-chdb]
            [oscope.server :as server]
            [teensyp.client :as client])
  (:import [java.io File]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- unsigned-prefix [bytes length]
  (mapv #(bit-and 255 %) (take length bytes)))

(defn- receive-all! [connection]
  (let [scratch (byte-array 8192)]
    (loop [chunks [] total 0]
      (if-let [length (client/receive-into!
                       connection scratch 0 (alength scratch)
                       {:timeout-ms 5000})]
        (let [chunk (byte-array length)]
          (System/arraycopy scratch 0 chunk 0 length)
          (recur (conj chunks chunk) (+ total length)))
        (let [result (byte-array total)]
          (loop [remaining chunks offset 0]
            (if-let [chunk (first remaining)]
              (do
                (System/arraycopy chunk 0 result offset (alength chunk))
                (recur (next remaining) (+ offset (alength chunk))))
              result)))))))

(defn- post-json! [port path value]
  (let [connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 5000})
        body (.getBytes (json/write-str value) "UTF-8")]
    (try
      (let [request (.getBytes
                     (str "POST " path " HTTP/1.1\r\n"
                          "Host: 127.0.0.1:" port "\r\n"
                          "Content-Type: application/json\r\n"
                          "Content-Length: " (alength body) "\r\n"
                          "Connection: close\r\n\r\n")
                     "UTF-8")]
        (client/send-all! connection request {:timeout-ms 5000})
        (client/send-all! connection body {:timeout-ms 5000})
        (let [response (String. (receive-all! connection) "UTF-8")
              [_ status] (re-find #"HTTP/1\.1 ([0-9]{3})" response)]
          (parse-long status)))
      (finally
        (client/close! connection)))))

(defn- trace-wire [now]
  {"resourceSpans"
   [{"resource"
     {"attributes" [{"key" "service.name"
                      "value" {"stringValue" "oscope-durable"}}]}
     "scopeSpans"
     [{"scope" {"name" "oscope.durable-integration"}
       "spans"
       [{"traceId" "11111111111111111111111111111111"
         "spanId" "2222222222222222"
         "name" "durable.ingest"
         "kind" 2
         "startTimeUnixNano" (str (- now 2000000))
         "endTimeUnixNano" (str (- now 1000000))}]}]}]})

(defn- log-wire [now]
  {"resourceLogs"
   [{"resource"
     {"attributes" [{"key" "service.name"
                      "value" {"stringValue" "oscope-durable"}}]}
     "scopeLogs"
     [{"scope" {"name" "oscope.durable-integration"}
       "logRecords"
       [{"timeUnixNano" (str (- now 1000000))
         "observedTimeUnixNano" (str now)
         "severityNumber" 9
         "severityText" "INFO"
         "body" {"stringValue" "durable integration log"}
         "traceId" "11111111111111111111111111111111"
         "spanId" "2222222222222222"}]}]}]})

(defn- metric-wire [now]
  {"resourceMetrics"
   [{"resource"
     {"attributes" [{"key" "service.name"
                      "value" {"stringValue" "oscope-durable"}}]}
     "scopeMetrics"
     [{"scope" {"name" "oscope.durable-integration"}
       "metrics"
       [{"name" "oscope.durable.gauge"
         "unit" "1"
         "gauge" {"dataPoints" [{"timeUnixNano" (str now)
                                  "asInt" "1"}]}}
        {"name" "oscope.durable.sum"
         "unit" "1"
         "sum" {"aggregationTemporality" 2
                "isMonotonic" true
                "dataPoints" [{"startTimeUnixNano" (str (- now 1000000))
                               "timeUnixNano" (str now)
                               "asInt" "4"}]}}
        {"name" "oscope.durable.histogram"
         "unit" "ms"
         "histogram"
         {"aggregationTemporality" 1
          "dataPoints" [{"startTimeUnixNano" (str (- now 1000000))
                         "timeUnixNano" (str now)
                         "count" "2"
                         "sum" 7.0
                         "bucketCounts" ["1" "1"]
                         "explicitBounds" [5.0]}]}}]}]}]})

(defn- delete-tree! [path]
  (let [file (.toFile ^Path path)]
    (when (.exists file)
      (doseq [child (or (.listFiles file) (make-array File 0))]
        (delete-tree! (.toPath child)))
      (Files/deleteIfExists ^Path path))))

(defn- error-type [f]
  (try
    (f)
    nil
    (catch Throwable error
      (loop [current error remaining 8]
        (if (and current (pos? remaining))
          (or (:type (ex-data current))
              (recur (ex-cause current) (dec remaining)))
          nil)))))

(defn- durable-server-options [store instance force?]
  {:port 0
   :durability {:checkpoint! jdbc.chdb.durable/checkpoint!
                :flush! jdbc.chdb.durable/flush!}
   :db-spec {:vendor "chdb-durable"
             :backend store
             :owner "oscope-test"
             :instance instance
             :database "default"
             :lease-ttl-ms 30000
             :force? force?}})

(defn- startup-error-type [options]
  (error-type
   #(let [lifecycle (server/start! options)]
      ;; An unexpectedly successful startup must not leak a native handle into
      ;; later tests, even though returning nil will still fail the assertion.
      (server/stop! lifecycle))))

(deftest startup-rejects-live-owner-and-forced-takeover-fences-it
  (let [root (Files/createTempDirectory
              "oscope-durable-takeover-" (make-array FileAttribute 0))]
    (try
      (let [store (local-posix/local-backend root)
            now (System/currentTimeMillis)
            old-token
            (:token
             (control/acquire!
              store {:owner "old-owner" :instance "old-instance"
                     :database "default"
                     :engine-version "26.7.2-rc.2"
                     :backup-format 1
                     :min-reader "26.7.2-rc.2"
                     :now now :expires-at (+ now 60000)}))]
        (is (= ::control/lease-held
               (startup-error-type
                (durable-server-options store "competing" false))))
        (let [lifecycle
              (server/start! (durable-server-options store "takeover" true))]
          (try
            (is (pos? (:port lifecycle)))
            (is (= ::control/lease-fenced
                   (error-type
                    #(control/renew! store old-token
                                     (+ (System/currentTimeMillis) 60000)))))
            (finally
              (is (= :closed (:status (server/stop! lifecycle))))))))
      (finally
        (delete-tree! root)))))

(deftest corrupt-head-fails-before-ingress
  (let [root (Files/createTempDirectory
              "oscope-durable-corrupt-" (make-array FileAttribute 0))]
    (try
      (let [store (local-posix/local-backend root)
            corrupt-bytes (.getBytes (str "{") "UTF-8")
            create-result
            (backend/put-bytes-if-absent!
             store control/head-key corrupt-bytes)]
        (is (= :created
               (:status create-result)))
        (is (= ::head/corrupt
               (startup-error-type
                (durable-server-options store "corrupt" false)))))
      (finally
        (delete-tree! root)))))

(deftest standalone-server-flushes-through-durable-jdbc-adapter
  (let [root (Files/createTempDirectory
              "oscope-durable-store-" (make-array FileAttribute 0))]
    (try
      (let [store (local-posix/local-backend root)
            lifecycle
            (server/start!
             {:port 0
              :durability {:checkpoint! jdbc.chdb.durable/checkpoint!
                           :flush! jdbc.chdb.durable/flush!}
              :db-spec {:vendor "chdb-durable"
                        :backend store
                        :owner "oscope-test"
                        :instance "oscope-test-instance"
                        :database "default"
                        :lease-ttl-ms 30000}})]
        (try
          (is (pos? (:port lifecycle)))
          (let [now (* (System/currentTimeMillis) 1000000)]
            (doseq [[path payload]
                    [["/v1/traces" (trace-wire now)]
                     ["/v1/logs" (log-wire now)]
                     ["/v1/metrics" (metric-wire now)]]]
              (is (= 200 (post-json! (:port lifecycle) path payload)))))
          (let [parquet ((:export-command (:source lifecycle))
                         :durable-integration
                         {:signal :spans :metric-kind nil
                          :start-unix-nano 0 :end-unix-nano 1
                          :format :parquet :max-rows 10
                          :max-bytes (* 4 1024 1024)})]
            (is (= [80 65 82 49]
                   (unsigned-prefix (:bytes parquet) 4)))
            (is (= "application/vnd.apache.parquet"
                   (:content-type parquet))))
          (is (= #{"oscope.durable.gauge"
                   "oscope.durable.sum"
                   "oscope.durable.histogram"}
                 (set
                  (map :value
                       (get-in
                        ((:load-command (:source lifecycle))
                         :durable-metrics
                         {:signal :metrics :field :metric-name
                          :window :15m :limit 10})
                        [:table :rows])))))
          (is (= {:status :closed :phase :closed}
                 (server/stop! lifecycle)))
          (finally
            (server/stop! lifecycle)))
        (let [head (:head (control/read-head! store))]
          (is (nil? (get-in head ["lease" "owner"])))
          (is (= 4 (get-in head ["manifest" "seq"])))
          (is (some? (get-in head ["manifest" "base"])))
          (is (= 3 (count (get-in head ["manifest" "wal"]))))
          ;; Schema migration history contains now64 and is intentionally
          ;; nondeterministic under statement replay. It must be folded into
          ;; the startup checkpoint, never retained in the ingest WAL.
          (doseq [reference (get-in head ["manifest" "wal"])]
            (let [payload (String. (backend/get-bytes
                                    store (get reference "key"))
                                   "UTF-8")]
              (is (not (str/includes? payload "otel_schema_migrations")))
              (is (not (str/includes? payload "now64"))))))
        ;; Reconstruct the provider from only its filesystem root before
        ;; opening a reader. This proves persistence through the advertised
        ;; backend boundary rather than through an in-process oracle atom.
        (with-open [reader
                    (jdbc/connection
                     {:vendor "chdb-durable"
                      :backend (local-posix/local-backend root)
                      :read-only? true})]
          (is (= 4 (:n (jdbc/fetch-one
                        reader
                        "select count() as n from otel_schema_migrations"))))
          (is (= 1 (:n (jdbc/fetch-one reader
                                        "select count() as n from otel_traces"))))
          (is (= 1 (:n (jdbc/fetch-one reader
                                        "select count() as n from otel_logs"))))
          (is (= 1 (:n (jdbc/fetch-one
                        reader
                        "select count() as n from otel_metrics_gauge"))))
          (is (= 1 (:n (jdbc/fetch-one
                        reader
                        "select count() as n from otel_metrics_sum"))))
          (is (= 1 (:n (jdbc/fetch-one
                        reader
                        "select count() as n from otel_metrics_histogram"))))
          (doseq [[signal kind]
                  [[:spans nil] [:logs nil] [:metrics :gauge]
                   [:metrics :sum] [:metrics :histogram]]]
            (let [selection {:signal signal :metric-kind kind
                             :start-unix-nano 0 :end-unix-nano 1
                             :max-rows 10 :max-bytes (* 4 1024 1024)}
                  parquet (raw-export-chdb/execute!
                           reader (assoc selection :format :parquet))
                  arrow (raw-export-chdb/execute!
                         reader (assoc selection :format :arrow))]
              (is (= [80 65 82 49]
                     (unsigned-prefix (:bytes parquet) 4)))
              (is (= [65 82 82 79 87 49]
                     (unsigned-prefix (:bytes arrow) 6)))))))
      (finally
        (delete-tree! root)))))
