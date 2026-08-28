(ns oscope.chdb-test-runner
  (:require [clojure.test :as test]
            [oscope.chdb-integration-test]
            [oscope.http-export-integration-test]
            [oscope.server-integration-test]))
(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.chdb-integration-test
                        'oscope.http-export-integration-test
                        'oscope.server-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
