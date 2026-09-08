(ns oscope.durable-s3-integration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [jdbc.core :as jdbc]
            [oscope.durable-server-main :as durable-main]
            [oscope.server :as server]
            [teensyp.client :as client]))

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

(defn- post-trace! [port now]
  (let [connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 5000})
        payload
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
               "endTimeUnixNano" (str (- now 1000000))}]}]}]}
        body (.getBytes (json/write-str payload) "UTF-8")]
    (try
      (let [request
            (.getBytes
             (str "POST /v1/traces HTTP/1.1\r\n"
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
         "OSCOPE_DURABLE_OBJECT_ID" "telemetry"
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
        (is (= 200 (post-trace! (:port lifecycle)
                                (* (System/currentTimeMillis) 1000000))))
        (finally
          (is (= :closed (:status (server/stop! lifecycle)))))))
    (let [db-spec (:db-spec (durable-main/durable-options
                             (assoc environment
                                    "OSCOPE_DURABLE_INSTANCE" "s3-reader")))
          store (backend/object-backend (:namespace-backend db-spec)
                                        (:object-id db-spec))
          head (:head (control/read-head! store))]
      (is (nil? (get-in head ["lease" "owner"])))
      (is (= 2 (get-in head ["manifest" "seq"])))
      (is (some? (get-in head ["manifest" "base"])))
      (is (= 1 (count (get-in head ["manifest" "wal"]))))
      (with-open [reader
                  (jdbc/connection
                   {:vendor "chdb-durable"
                    :namespace-backend (:namespace-backend db-spec)
                    :object-id (:object-id db-spec)
                    :read-only? true})]
        (is (= 1 (:n (jdbc/fetch-one
                      reader "select count() as n from otel_traces"))))))))
