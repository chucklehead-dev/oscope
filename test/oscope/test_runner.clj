(ns oscope.test-runner
  (:require [clojure.test :as test]
            [oscope.core-test]
            [oscope.live-test]
            [oscope.native-test]
            [oscope.otlp-test]
            [oscope.plotje-test]
            [oscope.query-view-test]
            [oscope.raw-export-test]
            [oscope.server-test]
            [oscope.web-test]))
(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.core-test 'oscope.live-test
                        'oscope.native-test 'oscope.otlp-test 'oscope.plotje-test
                        'oscope.query-view-test 'oscope.raw-export-test
                        'oscope.server-test 'oscope.web-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
