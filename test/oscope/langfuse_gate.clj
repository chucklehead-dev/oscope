(ns oscope.langfuse-gate
  "Shared synthetic trace and dual-destination helpers for Langfuse gates."
  (:require [clojure.string :as str]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.trace :as trace]))

(def root-name "oscope.langfuse.checkout")
(def child-name "oscope.langfuse.generate")

(def observation-fields
  "The only Langfuse observation fields retained by the live gate."
  [:id :traceId :parentObservationId :name :type :input :output])

(defn observation-view [observation]
  (select-keys observation observation-fields))

(defn memory-exporter [batches]
  (reify export/SpanExporter
    (export-spans! [_ spans]
      (swap! batches conj (vec spans))
      true)
    (flush-exporter! [_] true)
    (shutdown-exporter! [_] true)))

(defn emit-nested-trace!
  "Emit one root span and one generation child. Return their canonical IDs."
  []
  (let [tracer (sdk/tracer "oscope.langfuse-gate" {:version "1"})]
    (trace/with-span
      [root tracer root-name
       {:kind :server
        :attributes
        {:langfuse.observation.type "span"
         :langfuse.trace.name "oscope-langfuse-qualification"
         :langfuse.observation.input "qualification-input"
         :langfuse.observation.output "qualification-output"}}]
      (let [root-context (trace/span-context-of root)]
        (trace/with-span
          [child tracer child-name
           {:kind :client
            :attributes
            {:langfuse.observation.type "generation"
             :langfuse.observation.model.name "qualification-model"
             :langfuse.observation.input "generation-input"
             :langfuse.observation.output "generation-output"}}]
          (let [child-context (trace/span-context-of child)]
            {:trace-id (:trace-id root-context)
             :root-span-id (:span-id root-context)
             :child-span-id (:span-id child-context)}))))))

(defn span-identities [spans]
  (set
   (map (fn [span]
          {:trace-id (get-in span [:span-context :trace-id])
           :span-id (get-in span [:span-context :span-id])
           :parent-span-id (or (:parent-span-id span) "")
           :name (:name span)})
        spans)))

(defn wire-span-identities [payload]
  (set
   (for [resource-spans (:resourceSpans payload)
         scope-spans (:scopeSpans resource-spans)
         span (:spans scope-spans)]
     {:trace-id (:traceId span)
      :span-id (:spanId span)
      :parent-span-id (or (:parentSpanId span) "")
      :name (:name span)})))

(defn dual-sdk!
  "Start one SDK owner with independent local and remote bounded span queues."
  [{:keys [local-exporter remote-url remote-headers]}]
  (let [remote-exporter
        (otlp/exporter {:traces-url remote-url
                        :headers remote-headers
                        :environment? false
                        :timeout-ms 10000
                        :max-retries 0})
        pipelines
        (export/independent-batch-pipelines
         {:local {:exporter local-exporter
                  :config {:max-queue-size 32 :max-export-batch-size 16
                           :schedule-delay-ms 60000}}
          :remote {:exporter remote-exporter
                   :config {:max-queue-size 32 :max-export-batch-size 16
                            :schedule-delay-ms 60000}}})
        handle
        (try
          (sdk/init! {:service-name "oscope-langfuse-qualification"
                      :exporter :none
                      :span-processors [pipelines]
                      :metrics? false
                      :runtime-metrics? false
                      :logs? false})
          (catch Throwable error
            (try (export/shutdown-pipelines! pipelines)
                 (catch Throwable _ nil))
            (throw error)))]
    {:handle handle :pipelines pipelines}))

(defn normalized-headers [headers]
  (into {}
        (map (fn [[key value]]
               [(str/lower-case (if (keyword? key) (name key) (str key)))
                (str value)]))
        headers))
