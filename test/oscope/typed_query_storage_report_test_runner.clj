(ns oscope.typed-query-storage-report-test-runner
  (:require [clojure.test :as test]
            [oscope.typed-query-storage-report-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.typed-query-storage-report-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
