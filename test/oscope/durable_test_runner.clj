(ns oscope.durable-test-runner
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [oscope.durable-integration-test]
            [oscope.durable-server-main-test]
            [oscope.embedded-durable-integration-test]
            [oscope.embedded-query-test]
            [oscope.embedded-test]
            [oscope.otlp-test]
            [oscope.server-test]))

(defn -main [& _]
  (let [qualified? (= :supported (:status (native/durable-capability)))
        namespaces (cond-> ['oscope.durable-server-main-test
                            'oscope.embedded-test
                            'oscope.embedded-query-test
                            'oscope.otlp-test
                            'oscope.server-test]
                     qualified? (conj 'oscope.durable-integration-test
                                      'oscope.embedded-durable-integration-test))
        _ (when-not qualified?
            (println "SKIP: real Durable oscope integration requires the qualified chDB ABI"))
        result (apply test/run-tests namespaces)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "oscope Durable integration checks failed" result)))))
