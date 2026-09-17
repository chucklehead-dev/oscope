(ns oscope.durable-s3-integration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [jdbc.core :as jdbc]
            [oscope.config :as config]
            [oscope.durable-config-runtime :as durable-config-runtime]
            [oscope.durable-native-child-runner :as native-child]
            [oscope.raw-export.chdb :as raw-export-chdb]
            [oscope.server :as server]
            [teensyp.client :as client]))

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
              (do (System/arraycopy chunk 0 result offset (alength chunk))
                  (recur (next remaining) (+ offset (alength chunk))))
              result)))))))

(defn- post-json! [port path payload]
  (let [connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 5000})
        body (.getBytes (json/write-str payload) "UTF-8")]
    (try
      (let [request
            (.getBytes
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
                      "value" {"stringValue" "oscope-s3"}}]}
     "scopeSpans"
     [{"scope" {"name" "oscope.durable-s3-integration"}
       "spans"
       [{"traceId" "33333333333333333333333333333333"
         "spanId" "4444444444444444"
         "name" "durable.s3.ingest"
         "kind" 2
         "startTimeUnixNano" (str (- now 2000000))
         "endTimeUnixNano" (str (- now 1000000))}]}]}]})

(defn- log-wire [now]
  {"resourceLogs"
   [{"resource"
     {"attributes" [{"key" "service.name"
                      "value" {"stringValue" "oscope-s3"}}]}
     "scopeLogs"
     [{"scope" {"name" "oscope.durable-s3-integration"}
       "logRecords"
       [{"timeUnixNano" (str (- now 1000000))
         "observedTimeUnixNano" (str now)
         "severityNumber" 9
         "severityText" "INFO"
         "body" {"stringValue" "durable S3 integration log"}
         "traceId" "33333333333333333333333333333333"
         "spanId" "4444444444444444"}]}]}]})

(defn- metric-wire [now]
  {"resourceMetrics"
   [{"resource"
     {"attributes" [{"key" "service.name"
                      "value" {"stringValue" "oscope-s3"}}]}
     "scopeMetrics"
     [{"scope" {"name" "oscope.durable-s3-integration"}
       "metrics"
       [{"name" "oscope.s3.gauge"
         "unit" "1"
         "gauge" {"dataPoints" [{"timeUnixNano" (str now)
                                  "asInt" "1"}]}}
        {"name" "oscope.s3.sum"
         "unit" "1"
         "sum" {"aggregationTemporality" 2
                "isMonotonic" true
                "dataPoints" [{"startTimeUnixNano" (str (- now 1000000))
                               "timeUnixNano" (str now)
                               "asInt" "4"}]}}
        {"name" "oscope.s3.histogram"
         "unit" "ms"
         "histogram"
         {"aggregationTemporality" 1
          "dataPoints" [{"startTimeUnixNano" (str (- now 1000000))
                         "timeUnixNano" (str now)
                         "count" "2"
                         "sum" 7.0
                         "bucketCounts" ["1" "1"]
                         "explicitBounds" [5.0]}]}}]}]}]})

(defn- storage [endpoint instance]
  {:type :durable-s3
         :instance instance
         :lease-ttl-ms 30000
         :checkpoint-every-batches 2
         :s3 {:endpoint endpoint
              :bucket "oscope-durable"
              :prefix "integration"
              :region "us-east-1"
              :object-id "private-object-7f2c91"
              :credentials {:type :environment
                            :access-key-env "MINIO_ACCESS"
                            :secret-key-env "MINIO_SECRET"}}})

(defn- options [endpoint instance]
  ;; Public synthetic MinIO credentials, resolved afresh in each process.
  (durable-config-runtime/server-options
   (config/resolve-config
    [[:file {:version 2 :server {:port 0}
             :storage (storage endpoint instance)}]])
   {"MINIO_ACCESS" "MINIOACCESS" "MINIO_SECRET" "MINIOSECRET"}))

(defn- object-store [db-spec]
  (backend/object-backend (:namespace-backend db-spec) (:object-id db-spec)))

(defn- sha256 [text]
  (apply str
         (map #(format "%02x" (bit-and % 255))
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes text "UTF-8")))))

(defn checked-seal! [seal]
  ;; Freeze complete wire head identity, not only whichever seq was observed.
  (let [wire (get seal "head-json")]
    (when-not (and (= 1 (get seal "version"))
                   (string? (get seal "endpoint"))
                   (string? wire) (<= (count wire) 65536)
                   (= (sha256 wire) (get seal "head-sha256")))
      (throw (ex-info "invalid public S3 reader seal" {})))
    (let [head (json/read-str wire)]
      (when-not (and (nil? (get-in head ["lease" "owner"]))
                     (= 6 (get-in head ["manifest" "seq"]))
                     (some? (get-in head ["manifest" "base"]))
                     (= 1 (count (get-in head ["manifest" "wal"]))))
        (throw (ex-info "unexpected sealed S3 publication cadence" {})))
      head)))

(defn assert-fresh-reader! [seal-file]
  ;; The child has never owned the writer's native lifetime or backend handle.
  (let [seal (json/read-str (slurp seal-file))
        expected (checked-seal! seal)
        db-spec (:db-spec (options (get seal "endpoint") "s3-reader"))
        head (:head (control/read-head! (object-store db-spec)))]
    (is (= expected head) "reader sees the writer's complete frozen head")
    ;; A mismatch must prevent readback, not merely increment a test counter.
    (when-not (= expected head)
      (throw (ex-info "S3 head changed before independent readback" {})))
    (is (nil? (get-in head ["lease" "owner"])))
    (is (= 6 (get-in head ["manifest" "seq"])))
    (is (some? (get-in head ["manifest" "base"])))
    (is (= 1 (count (get-in head ["manifest" "wal"]))))
    (with-open [reader
                (jdbc/connection
                 (jdbc.chdb.durable/snapshot-dbspec
                  {:namespace-backend (:namespace-backend db-spec)
                   :object-id (:object-id db-spec)}))]
      (doseq [table ["otel_traces" "otel_logs" "otel_metrics_gauge"
                     "otel_metrics_sum" "otel_metrics_histogram"]]
        (is (= 1 (:n (jdbc/fetch-one
                      reader (str "select count() as n from " table))))))
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
          (is (= [80 65 82 49] (unsigned-prefix (:bytes parquet) 4)))
          (is (= [65 82 82 79 87 49] (unsigned-prefix (:bytes arrow) 6))))))))

(deftest standalone-server-recovers-through-reconstructed-s3-backend
  (let [endpoint (System/getenv "OSCOPE_TEST_S3_ENDPOINT")
        bucket-result
        (s3-curl/request!
         {:method :put :url (str endpoint "/oscope-durable")
          :headers {}
          :request-body {:bytes (byte-array 0) :byte-count 0}
          :auth {:access-key "MINIOACCESS" :secret-key "MINIOSECRET"}
          :region "us-east-1" :connect-timeout-ms 5000 :timeout-ms 30000})
        writer-options (options endpoint "s3-writer")
        store (object-store (:db-spec writer-options))]
    (is (= 200 (:status bucket-result)))
    (let [lifecycle (server/start! writer-options)]
      (try
        (is (pos? (:port lifecycle)))
        ;; Exporter and server each checkpoint startup; empty flushes do not
        ;; advance seq. Five physical tables are three logical export batches.
        (is (= 2 (get-in (control/read-head! store) [:head "manifest" "seq"])))
        (let [now (* (System/currentTimeMillis) 1000000)]
          (doseq [[path payload expected-seq]
                  [["/v1/traces" (trace-wire now) 3]
                   ["/v1/logs" (log-wire now) 5]
                   ["/v1/metrics" (metric-wire now) 6]]]
            (is (= 200 (post-json! (:port lifecycle) path payload)))
            (is (= expected-seq
                   (get-in (control/read-head! store) [:head "manifest" "seq"])))))
        (finally
          (is (= :closed (:status (server/stop! lifecycle)))))))
    (let [head (:head (control/read-head! store))
          wire (json/write-str head)
          seal-file (java.io.File/createTempFile "oscope-s3-reader-seal-" ".json")
          reader-settled (atom false)
          reader-qualified? (atom false)]
      (is (nil? (get-in head ["lease" "owner"])))
      (is (= 6 (get-in head ["manifest" "seq"])))
      (is (some? (get-in head ["manifest" "base"])))
      (is (= 1 (count (get-in head ["manifest" "wal"]))))
      (spit seal-file (json/write-str {"version" 1 "endpoint" endpoint
                                      "head-json" wire "head-sha256" (sha256 wire)}))
      (try
        (native-child/run-reader! :s3 seal-file reader-settled)
        (reset! reader-qualified? true)
        (finally
          ;; Retain semantic failure evidence even after physical settlement.
          (when (and @reader-qualified? @reader-settled)
            (.delete seal-file)))))))
