(ns oscope.headless-test-runner
  "Hosted-CI gate for the portable collector, query, export, and web layers.

  Native Glitter/Glimmer adapters remain in `oscope.test-runner`; real chDB,
  independent-reader, and standalone receiver gates retain their dedicated
  runners. This runner must not load GTK-facing namespaces."
  (:require [clojure.test :as test]
            [oscope.core-test]
            [oscope.live-test]
            [oscope.otlp-test]
            [oscope.plotje-test]
            [oscope.query-view-test]
            [oscope.raw-export-test]
            [oscope.sample-emitter-test]
            [oscope.server-test]
            [oscope.visualization-editor-test]
            [oscope.web-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'oscope.core-test
                        'oscope.live-test
                        'oscope.otlp-test
                        'oscope.plotje-test
                        'oscope.query-view-test
                        'oscope.raw-export-test
                        'oscope.sample-emitter-test
                        'oscope.server-test
                        'oscope.visualization-editor-test
                        'oscope.web-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
