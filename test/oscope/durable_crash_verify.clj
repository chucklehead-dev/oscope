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

(defn- verify! [db-spec expected-raw]
  (let [expected (parse-count expected-raw)]
    (with-open [connection
                (jdbc/connection db-spec)]
      (let [{:keys [n first_n second_n]}
            (jdbc/fetch-one
             connection
             (str "select count() as n, "
                  "countIf(SpanName = 'durable.crash.first') as first_n, "
                  "countIf(SpanName = 'durable.crash.second') as second_n "
                  "from otel_traces"))]
        (when-not (and (= expected n) (= 1 first_n) (= 1 second_n))
          (throw (ex-info "crash/reopen trace count mismatch"
                          {:oscope.durable-crash-verify/error true
                           :expected expected :actual n
                           :first first_n :second second_n})))
        (println (str "PASS: recovered " n
                      " flushed traces after process crashes"))))))

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
