(ns oscope.durable-fault-verify
  "Fresh read-only verification after a faulted writer has been SIGKILLed."
  (:require [db.jdbc]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]))

(defn- expectation [phase]
  (case phase
    "before" {:span-name "durable.fault.before" :expected 0
              :sequence 1 :wal-count 0}
    "after" {:span-name "durable.fault.after" :expected 1
             :sequence 2 :wal-count 1}
    (throw (ex-info "fault verification phase must be before or after"
                    {:oscope.durable-fault/error true}))))

(defn -main [root phase]
  (let [{:keys [span-name expected sequence wal-count]} (expectation phase)
        backend (local-posix/local-backend root)
        head (some-> (control/read-head-read-only! backend) :head)
        manifest (get head "manifest")]
    (when-not (and (= sequence (get manifest "seq"))
                   (= wal-count (count (get manifest "wal"))))
      (throw (ex-info "fault recovery head mismatch"
                      {:oscope.durable-fault/error true
                       :phase phase
                       :expected-sequence sequence
                       :actual-sequence (get manifest "seq")
                       :expected-wal-count wal-count
                       :actual-wal-count (count (get manifest "wal"))})))
    (with-open [connection
                (jdbc/connection
                 (jdbc.chdb.durable/snapshot-dbspec {:backend backend}))]
      (let [{:keys [n selected]}
            (jdbc/fetch-one
             connection
             (str "select count() as n, countIf(SpanName = '"
                  span-name "') as selected from otel_traces"))]
        (when-not (and (= expected n) (= expected selected))
          (throw (ex-info "fault recovery count mismatch"
                          {:oscope.durable-fault/error true
                           :phase phase
                           :expected expected
                           :actual n
                           :selected selected})))
        (println (str "PASS: " phase " fault returned no acknowledgement and "
                      "fresh recovery observed " n " rows"))))))
