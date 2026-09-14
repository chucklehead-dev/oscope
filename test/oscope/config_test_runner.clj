(ns oscope.config-test-runner
  (:require [clojure.test :as test]
            [oscope.config-test]
            [oscope.managed-config-posix-test]
            [oscope.managed-config-store-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.config-test
                        'oscope.managed-config-posix-test
                        'oscope.managed-config-store-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
