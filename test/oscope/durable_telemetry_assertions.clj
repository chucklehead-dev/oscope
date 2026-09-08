(ns oscope.durable-telemetry-assertions
  (:require [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(def ^:private telemetry-attribute-keys
  #{"jolt.durable.operation.name"
    "jolt.durable.operation.outcome"
    "jolt.durable.failure.category"
    "error.type"})

(defn validate!
  "Flush and validate the bounded telemetry emitted by woven Durable advice."
  [exporter handle private-values]
  (when-not (sdk/force-flush! handle)
    (throw (ex-info "Durable telemetry did not flush" {})))
  (let [spans (filter #(= "io.github.chucklehead-dev/oscope.durable"
                          (get-in % [:scope :name]))
                      (memory/spans exporter))
        durations (filter #(= "jolt.durable.operation.duration" (:name %))
                          (memory/metrics exporter))
        points (mapcat :data-points durations)
        operation-values
        (set (map #(get (:attributes %) "jolt.durable.operation.name") spans))
        printed (pr-str [spans durations])]
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
    (doseq [private-value private-values]
      (when (.contains printed private-value)
        (throw (ex-info "woven Durable telemetry retained private data"
                        {:secret-class :durable-private-data}))))
    [spans durations]))
