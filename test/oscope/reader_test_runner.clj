(ns oscope.reader-test-runner
  (:require [clojure.test :as test]
            [oscope.reader-integration-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.reader-integration-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
