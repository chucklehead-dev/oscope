(ns oscope.durable-crash-verify
  "Read-only verifier run after the crash/restart processes have exited."
  (:require [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.chdb.durable.s3-curl :as s3-curl]
            [jdbc.core :as jdbc]
            [oscope.durable-server-main :as durable-main]))

(defn- parse-count [raw]
  (let [value (parse-long raw)]
    (when-not (and (integer? value) (not (neg? value)))
      (throw (ex-info "expected count must be a nonnegative integer"
                      {:oscope.durable-crash-verify/error true})))
    value))

(defn- s3-reader-spec []
  (let [db-spec (:db-spec (durable-main/env-options))]
    (jdbc.chdb.durable/snapshot-dbspec
     {:namespace-backend (:namespace-backend db-spec)
      :object-id (:object-id db-spec)})))

(def ^:private expected-recovery-counts
  {:traces {:n 2 :first 1 :second 1}
   :logs {:n 2 :first 1 :second 1}
   :gauge {:n 2 :first 1 :second 1}
   :sum {:n 2 :first 1 :second 1}
   :histogram {:n 2 :first 1 :second 1}})

(defn assert-recovered-counts!
  "Require exactly-once recovery for the fixed two-process fixture identities.

  This remains deliberately closed over the five currently supported exporter
  tables. It detects loss, duplicate replay, and a substituted fixture identity
  without accepting arbitrary table/column/value input from the shell runner."
  [actual]
  (when-not (= expected-recovery-counts actual)
    (throw (ex-info "crash/reopen signal recovery mismatch"
                    {:oscope.durable-crash-verify/error true
                     :type ::recovery-mismatch})))
  true)

(defn- signal-counts [connection table column first-value second-value]
  ;; All identifiers and values are fixed fixture constants. Do not turn this
  ;; fresh-reader verifier into a general query surface.
  (jdbc/fetch-one
   connection
   (str "select count() as n, "
        "countIf(" column " = '" first-value "') as first, "
        "countIf(" column " = '" second-value "') as second "
        "from " table)))

(defn- recovered-counts [connection]
  {:traces (signal-counts connection "otel_traces" "SpanName"
                          "durable.crash.first" "durable.crash.second")
   :logs (signal-counts connection "otel_logs" "Body"
                        "durable.crash.first.log" "durable.crash.second.log")
   :gauge (signal-counts connection "otel_metrics_gauge" "MetricName"
                         "durable.crash.first.gauge" "durable.crash.second.gauge")
   :sum (signal-counts connection "otel_metrics_sum" "MetricName"
                       "durable.crash.first.sum" "durable.crash.second.sum")
   :histogram (signal-counts connection "otel_metrics_histogram" "MetricName"
                             "durable.crash.first.histogram"
                             "durable.crash.second.histogram")})

(defn- verify! [db-spec expected-raw]
  (let [expected (parse-count expected-raw)]
    (with-open [connection (jdbc/connection db-spec)]
      (let [actual (recovered-counts connection)]
        ;; Keep the command-line count as a separate, bounded invocation guard.
        ;; The closed per-signal oracle below is the actual no-loss/no-duplicate
        ;; evidence and cannot be weakened by changing one aggregate count.
        (when-not (= expected (get-in actual [:traces :n]))
          (throw (ex-info "crash/reopen process count mismatch"
                          {:oscope.durable-crash-verify/error true
                           :type ::process-count-mismatch})))
        (assert-recovered-counts! actual)
        (println (str "PASS: recovered exactly once through fresh Durable reader: "
                      "traces, logs, gauge, sum, histogram"))))))

(defn- create-s3-bucket! []
  (let [endpoint (or (System/getenv "OSCOPE_DURABLE_S3_ENDPOINT") "")
        bucket (or (System/getenv "OSCOPE_DURABLE_S3_BUCKET") "")
        result
        (s3-curl/request!
         {:method :put
          :url (str (str/replace endpoint #"/+$" "") "/" bucket)
          :headers {}
          :request-body {:bytes (byte-array 0) :byte-count 0}
          :auth {:access-key (System/getenv "OSCOPE_DURABLE_S3_ACCESS_KEY")
                 :secret-key (System/getenv "OSCOPE_DURABLE_S3_SECRET_KEY")
                 :session-token
                 (not-empty
                  (System/getenv "OSCOPE_DURABLE_S3_SESSION_TOKEN"))}
          :region (System/getenv "OSCOPE_DURABLE_S3_REGION")
          :connect-timeout-ms 5000 :timeout-ms 30000})]
    (when-not (contains? #{200 409} (:status result))
      (throw (ex-info "failed to create crash-test S3 bucket"
                      {:oscope.durable-crash-verify/error true
                       :status (:status result)})))
    (println (str "PASS: crash-test S3 bucket ready (HTTP "
                  (:status result) ")"))))

(defn -main [command & args]
  (case command
    "--create-s3-bucket" (create-s3-bucket!)
    "--verify-s3" (verify! (s3-reader-spec) (first args))
    "--verify-local"
    (let [[root expected] args]
      (verify! (jdbc.chdb.durable/snapshot-dbspec
                {:backend (local-posix/local-backend root)})
               expected))
    (throw (ex-info "expected --create-s3-bucket, --verify-s3, or --verify-local"
                    {:oscope.durable-crash-verify/error true}))))
