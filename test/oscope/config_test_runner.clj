(ns oscope.config-test-runner
  (:require [clojure.test :as test]
            [oscope.config-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'oscope.config-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
