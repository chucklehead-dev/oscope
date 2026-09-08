(ns oscope.durable-s3-aspect-test-runner
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [jolt.aspect-packs.chdb-durable.model :as durable-model]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-s3-integration-test]))

(defn -main [& _]
  (when-not (= :supported (:status (native/durable-capability)))
    (throw (ex-info "woven S3 integration requires the Durable chDB ABI" {})))
  (let [journal (history/journal)
        result
        (binding [history/*journal* journal
                  history/*context-id* :oscope-durable-s3-integration]
          (test/run-tests 'oscope.durable-s3-integration-test))
        failures (+ (:fail result) (:error result))
        events (history/events journal)]
    (when (pos? failures)
      (throw (ex-info "oscope Durable S3 integration checks failed" result)))
    (let [commands (durable-model/commands events)
          command-set (set (map :command commands))
          printed (pr-str events)]
      (when-not (every? command-set
                        [:acquire :checkpoint-publish :publish
                         :commit-attempt :release-attempt])
        (throw (ex-info "S3 history omitted a Durable control boundary"
                        {:commands (mapv :command commands)})))
      (when-not (every?
                 #(= :oscope-durable-s3-integration (:context-id %))
                 (filter #(= :invoke (:phase %)) events))
        (throw (ex-info "S3 history lost its test context" {})))
      (doseq [secret ["MINIOACCESS" "MINIOSECRET" "/oscope-durable"
                      "/integration/" "telemetry" "head.json"
                      "wal/" "checkpoints/"]]
        (when (.contains printed secret)
          (throw (ex-info "S3 history retained private data"
                          {:secret-class :durable-private-data}))))
      (history/assert-complete! journal)
      (println "oscope Durable S3 woven history validated"
               (count commands) "commands"))))
