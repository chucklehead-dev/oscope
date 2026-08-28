(ns oscope.test-runner
  (:require [clojure.test :as test]
            [oscope.core-test]
            [oscope.events-test]
            [oscope.live-test]
            [oscope.native-test]
            [oscope.native-server-test]
            [oscope.otlp-test]
            [oscope.plotje-test]
            [oscope.query-view-test]
            [oscope.raw-export-test]
            [oscope.sample-emitter-test]
            [oscope.server-test]
            [oscope.telemetry-test]
            [oscope.visualization-editor-test]
            [oscope.web-test]
            [oscope.workbench-test]))
(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.core-test 'oscope.events-test 'oscope.live-test
                        'oscope.native-test 'oscope.native-server-test
                        'oscope.otlp-test 'oscope.plotje-test
                        'oscope.query-view-test 'oscope.raw-export-test
                        'oscope.sample-emitter-test
                        'oscope.server-test 'oscope.visualization-editor-test
                        'oscope.telemetry-test 'oscope.web-test
                        'oscope.workbench-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
