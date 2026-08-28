(ns oscope.chdb-test-runner
  (:require [clojure.test :as test]
            [oscope.chdb-integration-test]))
(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.chdb-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
