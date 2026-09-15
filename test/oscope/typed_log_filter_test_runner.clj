(ns oscope.typed-log-filter-test-runner
  (:require [clojure.test :as test]
            [oscope.typed-log-filter-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.typed-log-filter-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
