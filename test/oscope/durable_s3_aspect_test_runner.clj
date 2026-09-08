(ns oscope.durable-s3-aspect-test-runner
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-history-assertions :as assertions]
            [oscope.durable-history-assertions-test]
            [oscope.durable-s3-integration-test]
            [oscope.durable-telemetry-assertions :as telemetry]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(defn -main [& _]
  (when-not (= :supported (:status (native/durable-capability)))
    (throw (ex-info "woven S3 integration requires the Durable chDB ABI" {})))
  (let [journal (history/journal)
        exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "oscope-durable-s3-aspect-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? false
                           :bridge-logging? false})
        private-values ["MINIOACCESS" "MINIOSECRET" "/oscope-durable"
                        "/integration/" "private-object-7f2c91" "head.json"
                        "wal/" "checkpoints/"]]
    (try
      (let [result
            (binding [history/*journal* journal
                      history/*context-id* :oscope-durable-s3-integration]
              (test/run-tests 'oscope.durable-history-assertions-test
                              'oscope.durable-s3-integration-test))
            failures (+ (:fail result) (:error result))
            events (history/events journal)]
        (when (pos? failures)
          (throw (ex-info "oscope Durable S3 integration checks failed" result)))
        (let [commands
              (assertions/assert-ingest-history!
               {:journal journal
                :events events
                :context-id :oscope-durable-s3-integration
                :private-values private-values})
              [spans _durations] (telemetry/validate!
                                  exporter handle private-values)]
          (println "oscope Durable S3 woven history and telemetry validated"
                   (count commands) "commands" (count spans) "spans")))
      (finally
        (sdk/shutdown! handle)))))
