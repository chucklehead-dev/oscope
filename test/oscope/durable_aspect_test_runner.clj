(ns oscope.durable-aspect-test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-history-assertions :as assertions]
            [oscope.durable-history-assertions-test]
            [oscope.durable-integration-test]
            [oscope.durable-telemetry-assertions :as telemetry]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(defn -main [& _]
  (let [journal (history/journal)
        exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "oscope-durable-aspect-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? false
                           :bridge-logging? false})
        private-values ["oscope-test" "oscope-test-instance" "head.json"
                        "wal/" "checkpoints/"]]
    (try
      (let [result (binding [history/*journal* journal
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
                :private-values private-values})
              [spans durations] (telemetry/validate!
                                 exporter handle private-values)
              printed (pr-str [events spans durations])]
          (doseq [private-value private-values]
            (when (.contains printed private-value)
              (throw (ex-info "oscope Durable diagnostics retained private data"
                              {:secret-class :durable-private-data}))))
          (println "oscope Durable woven history and telemetry validated"
                   (count commands) "commands" (count spans) "spans")))
      (finally
        (sdk/shutdown! handle)))))
