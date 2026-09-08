(ns oscope.durable-observability
  "Opt-in OpenTelemetry consumer for the existing chDB Durable control seams.

  Advice derives bounded diagnostics only from the join-point id, a closed
  return status, or a closed error category. It never inspects arguments,
  result content, exception messages, or exception data beyond `:type`."
  (:require [clojure.string :as str]
            [jolt.host :as host]
            [otel.context :as context]
            [otel.metrics :as metrics]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def durable-seam-revision
  "edc86af07d5982a185c4ef953c19c848a719da0e")

(def ^:private instrumentation-version "0.1.0")
(def ^:private scope-name "io.github.chucklehead-dev/oscope.durable")
(def ^:private duration-boundaries
  [0.001 0.005 0.01 0.05 0.1 0.5 1.0 5.0 10.0 30.0 120.0])
(defonce ^:private duration-instrument-cache (atom nil))

(def ^:private operation-names
  {:durable/acquire "acquire"
   :durable/publish-wal "publish"
   :durable/publish-checkpoint "checkpoint-publish"
   :durable/commit-reference "commit-attempt"
   :durable/renew "renew-attempt"
   :durable/release "release-attempt"})

(def ^:private successful-statuses
  #{:acquired :published :already-published :committed})

(defn- duration-instrument []
  (let [provider (sdk/meter-provider)]
    (if (nil? provider)
      metrics/noop-instrument
      (locking duration-instrument-cache
        (let [cached @duration-instrument-cache]
          (if (identical? provider (:provider cached))
            (:instrument cached)
            (let [instrument
                  (metrics/histogram
                   (sdk/meter scope-name {:version instrumentation-version})
                   "jolt.durable.operation.duration"
                   {:description "Duration of chDB Durable control operations."
                    :unit "s"
                    :boundaries duration-boundaries})]
              (reset! duration-instrument-cache
                      {:provider provider :instrument instrument})
              instrument)))))))

(defn- operation-name [join-point]
  (get operation-names (:id join-point) "unknown"))

(defn- return-attributes [operation result]
  (let [status (when (map? result) (:status result))]
    (cond
      (= :reconciled status)
      {:jolt.durable.operation.name operation
       :jolt.durable.operation.outcome "reconciled"
       :jolt.durable.failure.category "ambiguous"}

      (contains? successful-statuses status)
      {:jolt.durable.operation.name operation
       :jolt.durable.operation.outcome "success"}

      :else
      {:jolt.durable.operation.name operation
       :jolt.durable.operation.outcome "other"})))

(defn- error-category [error]
  (case (:type (ex-data error))
    :jdbc.chdb.durable.control/lease-fenced "fenced"
    :jdbc.chdb.durable.control/commit-ambiguous "ambiguous"
    "other"))

(defn- error-attributes [operation error]
  (let [category (error-category error)]
    {:jolt.durable.operation.name operation
     :jolt.durable.operation.outcome "error"
     :jolt.durable.failure.category category
     :error.type (str "jolt.durable." (str/replace category "-" "_"))}))

(defn- begin-observation [join-point]
  (try
    (let [operation (operation-name join-point)
          start-wall (host/wall-nanos)
          start-mono (host/mono-nanos)
          span (trace/start-span
                (sdk/tracer scope-name {:version instrumentation-version})
                (str "durable " operation)
                {:kind :internal
                 :attributes {:jolt.durable.operation.name operation}
                 :start-timestamp start-wall})]
      {:operation operation :start-wall start-wall :start-mono start-mono
       :span span :metric-attributes (atom nil)})
    (catch Throwable _ nil)))

(defn- observe-attributes! [{:keys [span metric-attributes]} attributes error?]
  (reset! metric-attributes attributes)
  (try
    (doseq [[key value] attributes]
      (trace/set-attribute! span key value))
    (when error?
      (trace/set-status! span :error "Durable control operation failed"))
    (catch Throwable _ nil)))

(defn- finish! [{:keys [span start-wall start-mono metric-attributes]}]
  (let [end-mono (try (host/mono-nanos) (catch Throwable _ nil))
        elapsed (when end-mono (max 0 (- end-mono start-mono)))]
    (try
      (if elapsed
        (trace/end! span (+ start-wall elapsed))
        (trace/end! span))
      (catch Throwable _ nil))
    (when (and elapsed @metric-attributes)
      (try
        (metrics/record! (duration-instrument)
                         (/ elapsed 1000000000.0)
                         @metric-attributes)
        (catch Throwable _ nil)))))

(defn around-control
  "Observe one existing Durable command without changing its application result.

  Generic OTel suppression is checked before clocks, SDK state, arguments, or
  result/error metadata are touched. This is the recursion barrier when the
  selected exporter writes into the same Durable-backed chDB connection."
  [join-point _evaluated-args proceed]
  (if (context/instrumentation-suppressed?)
    (proceed)
    (if-let [observation (begin-observation join-point)]
      (try
        (let [result (proceed)]
          (observe-attributes!
           observation (return-attributes (:operation observation) result) false)
          result)
        (catch Throwable error
          (observe-attributes!
           observation (error-attributes (:operation observation) error) true)
          (throw error))
        (finally
          (finish! observation)))
      (proceed))))

(def aspect-provider
  {:schema 1
   :libraries {'io.github.chucklehead-dev/jolt-chdb durable-seam-revision}
   :roles {:durable/control
           {:fn 'oscope.durable-observability/around-control
            :contract :args-v1}}})
