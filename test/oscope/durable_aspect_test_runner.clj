(ns oscope.durable-aspect-test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-history-assertions :as assertions]
            [oscope.durable-history-assertions-test]
            [oscope.durable-integration-test]))

(defn -main [& _]
  (let [journal (history/journal)
        result (binding [history/*journal* journal
                         history/*context-id* :oscope-durable-integration]
                 (test/run-tests 'oscope.durable-history-assertions-test
                                 'oscope.durable-integration-test))
        failures (+ (:fail result) (:error result))
        events (history/events journal)]
    (when (pos? failures)
      (throw (ex-info "oscope Durable integration checks failed" result)))
    (let [commands
          (assertions/assert-ingest-history!
           {:journal journal
            :events events
            :context-id :oscope-durable-integration
            :private-values ["oscope-test" "oscope-test-instance" "head.json"
                             "wal/" "checkpoints/"]})]
      (println "oscope Durable woven history validated"
               (count commands) "commands"))))
