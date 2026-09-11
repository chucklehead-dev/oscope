(ns oscope.embedded-durable-integration-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
            [oscope.embedded.query :as embedded-query]
            [oscope.live :as live]
            [oscope.sample-emitter :as sample]
            [otel.otlp.http :as otlp-http]))

(defn- values [source request-id signal field]
  (set
   (map :value
        (get-in
         ((:load-command source)
          request-id
          {:signal signal :field field :window :15m :limit 20})
         [:table :rows]))))

(defn- assert-signals-visible! [source suffix]
  (is (contains? (values source (keyword (str "spans-" suffix))
                         :spans :span-name)
                 "POST /checkout"))
  (is (contains? (values source (keyword (str "logs-" suffix))
                         :logs :severity-text)
                 "INFO"))
  (is (= #{"sample.checkout.requests"
           "sample.checkout.queue.depth"
           "sample.checkout.duration"}
         (values source (keyword (str "metrics-" suffix))
                 :metrics :metric-name))))

(defn- await-ready [lifecycle]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [snapshot (embedded-query/snapshot lifecycle)]
        (cond
          (= :ready (:status snapshot)) snapshot
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 5) (recur))
          :else snapshot)))))

(deftest direct-sdk-exports-survive-a-fresh-durable-reader
  (let [store (backend/memory-backend)
        db-spec (jdbc.chdb.durable/writer-dbspec
                 {:backend store
                  :owner "oscope-embedded-test"
                  :instance "oscope-embedded-test-instance"
                  :database "default"
                  :lease-ttl-ms 30000})
        lifecycle
        (embedded/start!
         {:db-spec db-spec
          :sdk-options {:service-name "oscope-embedded-test"
                        :processor :simple
                        :metrics? true
                        :runtime-metrics? false
                        :logs? true
                        :bridge-logging? false}})]
    (try
      (sample/emit-scenario!)
      (is (true? (embedded/force-flush! lifecycle)))
      (assert-signals-visible! (:source lifecycle) "writer")
      ;; This is the real counted-lock regression: the cadence runs on a Jolt
      ;; fiber, but the live source/JDBC query must execute on the facade's OS
      ;; thread rather than attempting to park the fiber while locks are held.
      (let [query-lifecycle
            (embedded-query/start!
             {:load! #(get-in
                       ((:load-command (:source lifecycle))
                        :embedded-background-query
                        {:signal :spans :field :span-name
                         :window :15m :limit 20})
                       [:table :rows])
              :interval-ms 1000 :timeout-ms 2000 :stop-timeout-ms 2000})]
        (try
          (let [snapshot (await-ready query-lifecycle)]
            (is (= :ready (:status snapshot)))
            (is (some #(= "POST /checkout" (:value %)) (:rows snapshot))))
          (finally
            ;; The query thread is joined before embedded/stop! retires source.
            (is (= :closed (:status
                            (embedded-query/stop! query-lifecycle)))))))
      (is (= {:status :closed :phase :closed}
             (embedded/stop! lifecycle)))
      (with-open [reader (jdbc/connection
                          (jdbc.chdb.durable/snapshot-dbspec {:backend store}))]
        (let [source (live/open! {:connection reader :ensure-schema? false})]
          (try
            (assert-signals-visible! source "reader")
            (finally
              (live/close! source)))))
      (finally
        (embedded/stop! lifecycle)))))

(defn- wire-spans [payload]
  (for [resource-spans (:resourceSpans payload)
        scope-spans (:scopeSpans resource-spans)
        span (:spans scope-spans)]
    {:trace-id (:traceId span)
     :span-id (:spanId span)
     :parent-span-id (or (:parentSpanId span) "")}))

(deftest dual-export-preserves-local-and-remote-trace-identity
  (let [store (backend/memory-backend)
        requests (atom [])
        db-spec (jdbc.chdb.durable/writer-dbspec
                 {:backend store
                  :owner "oscope-embedded-dual-test"
                  :instance "oscope-embedded-dual-test-instance"
                  :database "default"
                  :lease-ttl-ms 30000})]
    (with-redefs [otlp-http/post
                  (fn [url body options]
                    (swap! requests conj {:url url :body body :options options})
                    {:status 200 :body "{}"})]
      (let [lifecycle
            (embedded/start!
             {:db-spec db-spec
              :sdk-options {:service-name "oscope-embedded-dual-test"
                            :metrics? true
                            :runtime-metrics? false
                            :logs? true
                            :bridge-logging? false}
              :span-pipelines
              {:local {:schedule-delay-ms 60000}
               :remote {:endpoint "http://collector.invalid:4318"
                        :max-retries 0
                        :schedule-delay-ms 60000}}})]
        (try
          (sample/emit-scenario!)
          (is (= {:sdk {:ok? true}
                  :span-pipelines
                  {:local {:ok? true} :remote {:ok? true}}}
                 (embedded/force-flush! lifecycle)))
          (let [local
                (set
                 (map (fn [row]
                        {:trace-id (:traceid row)
                         :span-id (:spanid row)
                         :parent-span-id (:parentspanid row)})
                      (jdbc/fetch
                       (:connection lifecycle)
                       "select TraceId, SpanId, ParentSpanId from otel_traces")))
                remote
                (set
                 (mapcat
                  (fn [{:keys [body]}]
                    (wire-spans (json/read-str body :key-fn keyword)))
                  @requests))]
            (is (= local remote))
            (is (= 5 (count local)))
            (is (every? #(= "http://collector.invalid:4318/v1/traces"
                            (:url %))
                        @requests)
                "the remote exporter receives spans only")
            (let [stopped (embedded/stop! lifecycle)]
              (is (= {:ok? true} (get-in stopped [:telemetry :sdk])))
              (is (= #{{:ok? true}}
                     (set (vals (get-in stopped
                                        [:telemetry :span-pipelines])))))))
          (finally
            (embedded/stop! lifecycle)))))))
