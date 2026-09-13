(ns oscope.typed-socket-test-runner
  "Bounded hosted gate for typed direct and real loopback socket ingestion."
  (:require [clojure.test :as test]
            [db.jdbc]
            [oscope.typed-standalone-restart-integration-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.typed-standalone-restart-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
