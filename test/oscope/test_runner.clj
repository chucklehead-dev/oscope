(ns oscope.test-runner
  (:require [clojure.test :as test]
            [oscope.async-selection-test]
            [oscope.config-property-test]
            [oscope.config-test]
            [oscope.core-test]
            [oscope.embedded-query-test]
            [oscope.events-test]
            [oscope.http-executor-test]
            [oscope.live-test]
            [oscope.native-test]
            [oscope.native-server-test]
            [oscope.otlp-test]
            [oscope.plotje-property-test]
            [oscope.plotje-test]
            [oscope.query-expression-test]
            [oscope.query-expression-property-test]
            [oscope.query-view-test]
            [oscope.raw-export-test]
            [oscope.sample-emitter-test]
            [oscope.server-test]
            [oscope.telemetry-test]
            [oscope.typed-schema-config-test]
            [oscope.typed-schema-runtime-test]
            [oscope.typed-schema-test]
            [oscope.typed-span-filter-test]
            [oscope.visualization-editor-test]
            [oscope.web-test]
            [oscope.workbench-test]))
(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.async-selection-test
                        'oscope.config-property-test 'oscope.config-test
                        'oscope.core-test 'oscope.embedded-query-test
                        'oscope.events-test 'oscope.live-test
                        'oscope.http-executor-test
                        'oscope.native-test 'oscope.native-server-test
                        'oscope.otlp-test 'oscope.plotje-property-test
                        'oscope.plotje-test
                        'oscope.query-expression-test
                        'oscope.query-expression-property-test
                        'oscope.query-view-test 'oscope.raw-export-test
                        'oscope.sample-emitter-test
                        'oscope.server-test 'oscope.visualization-editor-test
                        'oscope.telemetry-test
                        'oscope.typed-schema-config-test
                        'oscope.typed-schema-runtime-test
                        'oscope.typed-schema-test
                        'oscope.typed-span-filter-test
                        'oscope.web-test
                        'oscope.workbench-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
