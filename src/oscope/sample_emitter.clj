(ns oscope.sample-emitter
  "One-shot companion application that sends a coherent sample workload to an
  OTLP/HTTP collector. The sample uses the public OTel SDK APIs, so it doubles as
  a compact example of instrumenting a Jolt application."
  (:require [clojure.string :as str]
            [jolt.http-client :as http]
            [otel.logs :as logs]
            [otel.metrics :as metrics]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def default-endpoint "http://127.0.0.1:4318")

(defn endpoint
  "Resolve the collector base endpoint from an explicit value or the standard
  OTel environment variable. A trailing slash is removed before signal paths
  are appended by the exporter."
  ([] (endpoint nil))
  ([explicit]
   (str/replace (or explicit
                    (jolt.host/getenv "OTEL_EXPORTER_OTLP_ENDPOINT")
                    default-endpoint)
                #"/+$" "")))

(defn- collector-ready! [base]
  (let [response (http/get (str base "/healthz")
                           {:connection-timeout 3000
                            :socket-timeout 3000
                            :throw-exceptions false})]
    (when-not (= 200 (:status response))
      (throw (ex-info (str "oscope sample emitter: collector health check failed: HTTP "
                           (:status response))
                      {:endpoint base :status (:status response)})))
    true))

(defn emit-scenario!
  "Emit one nested checkout trace, correlated logs, and three metric kinds.
  Requires an initialized OTel SDK and returns the emitted trace id."
  []
  (let [tracer (sdk/tracer "oscope.sample.checkout" {:version "1.0"})
        logger (sdk/logger "oscope.sample.checkout" {:version "1.0"})
        meter (sdk/meter "oscope.sample.checkout" {:version "1.0"})
        requests (metrics/counter meter "sample.checkout.requests"
                                  {:description "sample checkouts accepted"
                                   :unit "{request}"})
        queue-depth (metrics/gauge meter "sample.checkout.queue.depth"
                                   {:description "sample checkout jobs waiting"
                                    :unit "{job}"})
        latency (metrics/histogram meter "sample.checkout.duration"
                                   {:description "sample checkout latency"
                                    :unit "ms"
                                    :boundaries [10.0 25.0 50.0 100.0 250.0]})]
    (metrics/add! requests 1 {:checkout.channel "web"})
    (metrics/set-value! queue-depth 2 {:queue.name "fulfillment"})
    (metrics/record! latency 47.0 {:checkout.channel "web"})
    (trace/with-span
      [request tracer "POST /checkout"
       {:kind :server
        :attributes {:http.request.method "POST"
                     :http.route "/checkout"
                     :http.response.status_code 202}}]
      (let [trace-id (:trace-id (trace/span-context-of request))]
        (logs/emit! logger {:body "checkout request accepted"
                            :severity :info
                            :attributes {:order.id "sample-order-42"}})
        (trace/with-span
          [validate tracer "validate cart"
           {:attributes {:cart.item.count 3}}]
          (trace/add-event! validate "cart.validated" {:validation.rules 4})
          (trace/set-status! validate :ok))
        (trace/with-span
          [inventory tracer "GET /inventory/{sku}"
           {:kind :client
            :attributes {:http.request.method "GET"
                         :server.address "inventory.local"
                         :http.response.status_code 200}}]
          (logs/emit! logger {:body "inventory reservation started"
                              :severity :info
                              :attributes {:inventory.sku "planet-brain"}})
          (trace/with-span
            [query tracer "SELECT inventory"
             {:kind :client
              :attributes {:db.system.name "clickhouse"
                           :db.operation.name "SELECT"
                           :db.namespace "inventory"
                           :db.response.returned_rows 1
                           :db.query.summary "lookup inventory by sku"}}]
            (trace/set-status! query :ok))
          (trace/set-status! inventory :ok))
        (trace/with-span
          [publish tracer "fulfillment enqueue"
           {:kind :producer
            :attributes {:messaging.system "sample-queue"
                         :messaging.destination.name "fulfillment"
                         :messaging.operation.name "publish"}}]
          (trace/add-event! publish "job.enqueued" {:job.id "sample-job-42"})
          (trace/set-status! publish :ok))
        (logs/emit! logger {:body "checkout workflow completed"
                            :severity :info
                            :attributes {:order.id "sample-order-42"
                                         :checkout.result "accepted"}})
        (trace/set-status! request :ok)
        trace-id))))

(defn run!
  "Send one scenario to `base`, returning its trace id. The SDK is always shut
  down, which flushes the one-shot metric snapshot and releases its workers."
  ([] (run! {}))
  ([opts]
   (let [base (endpoint (:endpoint opts))]
     (collector-ready! base)
     (let [handle (sdk/init! {:service-name "oscope-sample-app"
                              :endpoint base
                              :exporter :otlp
                              :processor :simple
                              :metrics? true
                              :runtime-metrics? false
                              :metric-interval-ms 60000
                              :logs? true
                              :bridge-logging? false})]
       (try
         (emit-scenario!)
         (finally
           (when-not (sdk/shutdown! handle)
             (throw (ex-info "oscope sample emitter: OTel shutdown failed"
                             {:endpoint base})))))))))

(defn -main [& [base]]
  (let [base (endpoint base)
        trace-id (run! {:endpoint base})]
    (println (str "Emitted sample trace " trace-id ", logs, and metrics to " base))
    (println (str "View them at " base "/oscope"))))
