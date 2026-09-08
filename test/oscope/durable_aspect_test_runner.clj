(ns oscope.durable-aspect-test-runner
  (:require [clojure.test :as test]
            [jolt.aspect-packs.history :as history]
            [oscope.durable-history-assertions :as assertions]
            [oscope.durable-history-assertions-test]
            [oscope.durable-integration-test]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(def ^:private telemetry-attribute-keys
  #{"jolt.durable.operation.name"
    "jolt.durable.operation.outcome"
    "jolt.durable.failure.category"
    "error.type"})

(defn- validate-telemetry! [exporter handle]
  (when-not (sdk/force-flush! handle)
    (throw (ex-info "Durable telemetry did not flush" {})))
  (let [spans (filter #(= "io.github.chucklehead-dev/oscope.durable"
                          (get-in % [:scope :name]))
                      (memory/spans exporter))
        durations (filter #(= "jolt.durable.operation.duration" (:name %))
                          (memory/metrics exporter))
        points (mapcat :data-points durations)
        operation-values
        (set (map #(get (:attributes %) "jolt.durable.operation.name") spans))]
    (when (empty? spans)
      (throw (ex-info "woven Durable telemetry emitted no spans" {})))
    (when (empty? points)
      (throw (ex-info "woven Durable telemetry emitted no durations" {})))
    (when-not (every? #(every? telemetry-attribute-keys
                               (keys (:attributes %)))
                      (concat spans points))
      (throw (ex-info "woven Durable telemetry used an unbounded attribute" {})))
    (when-not (every? operation-values
                      ["acquire" "checkpoint-publish" "publish"
                       "commit-attempt" "release-attempt"])
      (throw (ex-info "woven Durable telemetry omitted a control boundary"
                      {:operations operation-values})))
    [spans durations]))

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
              [spans durations] (validate-telemetry! exporter handle)
              printed (pr-str [events spans durations])]
          (doseq [private-value private-values]
            (when (.contains printed private-value)
              (throw (ex-info "oscope Durable diagnostics retained private data"
                              {:secret-class :durable-private-data}))))
          (println "oscope Durable woven history and telemetry validated"
                   (count commands) "commands" (count spans) "spans")))
      (finally
        (sdk/shutdown! handle)))))
