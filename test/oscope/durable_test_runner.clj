(ns oscope.durable-test-runner
  "Manual callers must provide an outer wall bound (CI:415s plus5s kill grace).
  Parent exit is not a claim of universal descendant quiescence."
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [oscope.durable-native-child-runner :as native-child]
            [oscope.durable-native-child-runner-test]
            [oscope.durable-config-runtime-test]
            [oscope.durable-integration-test]
            [oscope.durable-server-main-test]
            [oscope.embedded-durable-integration-test]
            [oscope.embedded-query-test]
            [oscope.embedded-test]
            [oscope.embedded-viewer-test]
            [oscope.otlp-test]
            [oscope.server-test]))

(defn -main [& _]
  (let [qualified? (= :supported (:status (native/durable-capability)))
        namespaces ['oscope.durable-config-runtime-test
                            'oscope.durable-server-main-test
                            'oscope.embedded-test
                            'oscope.embedded-viewer-test
                            'oscope.embedded-query-test
                            'oscope.otlp-test
                            'oscope.server-test
                            'oscope.durable-native-child-runner-test]
        _ (when-not qualified?
            (println "SKIP: real Durable oscope integration requires the qualified chDB ABI"))
        result (apply test/run-tests namespaces)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "oscope Durable integration checks failed" result)))
    (when qualified?
      (let [directory (str (java.nio.file.Files/createTempDirectory
                            "oscope-durable-native-children-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
            outcome (native-child/run-isolated! (native-child/executable!) directory)]
        (println :durable-native-total (:totals outcome))
        (when-not (and (:qualified? outcome) (:settled? outcome)
                       (= 6 (get-in outcome [:totals :test])))
          (throw (ex-info "isolated native fixtures did not qualify" {})))))))
