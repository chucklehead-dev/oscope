(ns oscope.durable-aspect-test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.chdb-durable.model :as durable-model]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-integration-test]))

(defn -main [& _]
  (let [journal (history/journal)
        result (binding [history/*journal* journal
                         history/*context-id* :oscope-durable-integration]
                 (test/run-tests 'oscope.durable-integration-test))
        failures (+ (:fail result) (:error result))
        events (history/events journal)]
    (when (pos? failures)
      (throw (ex-info "oscope Durable integration checks failed" result)))
    (let [commands (durable-model/commands events)
          command-set (set (map :command commands))
          printed (pr-str events)]
      (when-not (every? command-set
                        [:acquire :checkpoint-publish :publish
                         :commit-attempt :release-attempt])
        (throw (ex-info "oscope Durable history omitted a control boundary"
                        {:commands (mapv :command commands)})))
      (when-not (every? #(= :oscope-durable-integration (:context-id %))
                        (filter #(= :invoke (:phase %)) events))
        (throw (ex-info "oscope Durable history lost its test context" {})))
      (doseq [secret ["oscope-test" "oscope-test-instance" "head.json"
                      "wal/" "checkpoints/"]]
        (when (.contains printed secret)
          (throw (ex-info "oscope Durable history retained private data"
                          {:secret-class :durable-private-data}))))
      (history/assert-complete! journal)
      (println "oscope Durable woven history validated"
               (count commands) "commands"))))
