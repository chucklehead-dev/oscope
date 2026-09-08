(ns oscope.durable-s3-integration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [jdbc.core :as jdbc]
            [oscope.durable-server-main :as durable-main]
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

(deftest standalone-server-recovers-through-reconstructed-s3-backend
  (let [endpoint (System/getenv "OSCOPE_TEST_S3_ENDPOINT")
        auth {:access-key "MINIOACCESS" :secret-key "MINIOSECRET"}
        bucket-result
        (s3-curl/request!
         {:method :put :url (str endpoint "/oscope-durable")
          :headers {}
          :request-body {:bytes (byte-array 0) :byte-count 0}
          :auth auth :region "us-east-1"
          :connect-timeout-ms 5000 :timeout-ms 30000})
        environment
        {"OSCOPE_DURABLE_BACKEND" "s3"
         "OSCOPE_DURABLE_OBJECT_ID" "private-object-7f2c91"
         "OSCOPE_DURABLE_S3_ENDPOINT" endpoint
         "OSCOPE_DURABLE_S3_BUCKET" "oscope-durable"
         "OSCOPE_DURABLE_S3_PREFIX" "integration"
         "OSCOPE_DURABLE_S3_REGION" "us-east-1"
         "OSCOPE_DURABLE_S3_ACCESS_KEY" (:access-key auth)
         "OSCOPE_DURABLE_S3_SECRET_KEY" (:secret-key auth)
         "OSCOPE_DURABLE_INSTANCE" "s3-writer"
         "OSCOPE_DURABLE_LEASE_TTL_MS" "30000"
         "OSCOPE_PORT" "0"}
        options (durable-main/durable-options environment)]
    (is (= 200 (:status bucket-result)))
    (let [lifecycle (server/start! options)]
      (try
        (is (pos? (:port lifecycle)))
        (let [now (* (System/currentTimeMillis) 1000000)]
          (doseq [[path payload]
                  [["/v1/traces" (trace-wire now)]
                   ["/v1/logs" (log-wire now)]
                   ["/v1/metrics" (metric-wire now)]]]
            (is (= 200 (post-json! (:port lifecycle) path payload)))))
        (finally
          (is (= :closed (:status (server/stop! lifecycle)))))))
    (let [db-spec (:db-spec (durable-main/durable-options
                             (assoc environment
                                    "OSCOPE_DURABLE_INSTANCE" "s3-reader")))
          store (backend/object-backend (:namespace-backend db-spec)
                                        (:object-id db-spec))
          head (:head (control/read-head! store))]
      (is (nil? (get-in head ["lease" "owner"])))
      (is (= 4 (get-in head ["manifest" "seq"])))
      (is (some? (get-in head ["manifest" "base"])))
      (is (= 3 (count (get-in head ["manifest" "wal"]))))
      (with-open [reader
                  (jdbc/connection
                   {:vendor "chdb-durable"
                    :namespace-backend (:namespace-backend db-spec)
                    :object-id (:object-id db-spec)
                    :read-only? true})]
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
            (is (= [80 65 82 49]
                   (unsigned-prefix (:bytes parquet) 4)))
            (is (= [65 82 82 79 87 49]
                   (unsigned-prefix (:bytes arrow) 6)))))))))
