(ns oscope.durable-crash-verify
  "Read-only verifier run after the crash/restart processes have exited."
  (:require [db.jdbc]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]))

(defn- parse-count [raw]
  (let [value (parse-long raw)]
    (when-not (and (integer? value) (not (neg? value)))
      (throw (ex-info "expected count must be a nonnegative integer"
                      {:oscope.durable-crash-verify/error true})))
    value))

(defn -main [root expected-raw & _]
  (let [expected (parse-count expected-raw)]
    (with-open [connection
                (jdbc/connection
                 {:vendor "chdb-durable"
                  :backend (local-posix/local-backend root)
                  :read-only? true})]
      (let [actual (:n (jdbc/fetch-one
                        connection
                        "select count() as n from otel_traces"))]
        (when-not (= expected actual)
          (throw (ex-info "crash/reopen trace count mismatch"
                          {:oscope.durable-crash-verify/error true
                           :expected expected :actual actual})))
        (println (str "PASS: recovered " actual
                      " flushed traces after process crashes"))))))
