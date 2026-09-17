(ns oscope.headless-test-runner
  "Hosted-CI gate for the portable collector, query, export, and web layers.

  Native Glitter/Glimmer adapters remain in `oscope.test-runner`; independent
  reader and standalone receiver gates retain their dedicated runners. The
  minimal embedded-profile acceptance opens real SQLite and chDB state in a
  fresh child process. This runner must not load GTK-facing namespaces."
  (:require [clojure.test :as test]
            [oscope.async-selection-test]
            [oscope.core-test]
            [oscope.config-test]
            [oscope.config-property-test]
            [oscope.durable-cadence-property-test]
            [oscope.durable-config-runtime-test]
            [oscope.durable-observability-test]
            [oscope.dependency-resolution-test]
            [oscope.embedded-profile-test]
            [oscope.embedded-query-test]
            [oscope.embedded-viewer-test]
            [oscope.events-test]
            [oscope.http-executor-test]
            [oscope.langfuse-profile-test]
            [oscope.live-test]
            [oscope.otlp-test]
            [oscope.plotje-property-test]
            [oscope.plotje-test]
            [oscope.query-expression-test]
            [oscope.query-expression-property-test]
            [oscope.query-trace-test]
            [oscope.query-view-test]
            [oscope.raw-export-test]
            [oscope.readiness-test]
            [oscope.readiness-listener-test]
            [oscope.readiness-posix-options-test]
            [oscope.runtime-floor-test]
            [oscope.sample-emitter-test]
            [oscope.server-test]
            [oscope.telemetry-test]
            [oscope.typed-schema-config-test]
            [oscope.typed-schema-runtime-test]
            [oscope.typed-schema-test]
            [oscope.typed-log-filter-test]
            [oscope.typed-span-aggregate-test]
            [oscope.typed-span-filter-test]
            [oscope.visualization-editor-test]
            [oscope.web-test]
            [oscope.workbench-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.async-selection-test
                        'oscope.core-test
                        'oscope.config-test
                        'oscope.config-property-test
                        'oscope.durable-cadence-property-test
                        'oscope.durable-config-runtime-test
                        'oscope.durable-observability-test
                        'oscope.dependency-resolution-test
                        'oscope.embedded-profile-test
                        'oscope.embedded-query-test
                        'oscope.embedded-viewer-test
                        'oscope.events-test
                        'oscope.http-executor-test
                        'oscope.langfuse-profile-test
                        'oscope.live-test
                        'oscope.otlp-test
                        'oscope.plotje-property-test
                        'oscope.plotje-test
                        'oscope.query-expression-test
                        'oscope.query-expression-property-test
                        'oscope.query-trace-test
                        'oscope.query-view-test
                        'oscope.raw-export-test
                        'oscope.readiness-test
                        'oscope.readiness-listener-test
                        'oscope.readiness-posix-options-test
                        'oscope.runtime-floor-test
                        'oscope.sample-emitter-test
                        'oscope.server-test
                        'oscope.telemetry-test
                        'oscope.typed-schema-config-test
                        'oscope.typed-schema-runtime-test
                        'oscope.typed-schema-test
                        'oscope.typed-log-filter-test
                        'oscope.typed-span-aggregate-test
                        'oscope.typed-span-filter-test
                        'oscope.visualization-editor-test
                        'oscope.web-test
                        'oscope.workbench-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
