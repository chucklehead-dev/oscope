(ns oscope.durable-observability-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.host :as host]
            [oscope.durable-observability :as observability]
            [otel.context :as context]
            [otel.exporter.memory :as memory]
            [otel.metrics :as metrics]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- join-point [operation]
  {:id operation
   :advice-role :durable/control
   :contract :args-v1
   :library {:id 'io.github.chucklehead-dev/jolt-chdb
             :version observability/durable-seam-revision}})

(defn- with-memory-sdk [f]
  (let [exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "oscope-durable-observability-test"
                           :exporter exporter
                           :processor :simple
                           :runtime-metrics? false
                           :logs? false
                           :bridge-logging? false})]
    (try
      (f exporter handle)
      (finally
        (sdk/shutdown! handle)))))

(defn- attribute [telemetry key]
  (get (:attributes telemetry)
       (if (keyword? key) (subs (str key) 1) key)))

(def ^:private bounded-attribute-keys
  #{"jolt.durable.operation.name"
    "jolt.durable.operation.outcome"
    "jolt.durable.failure.category"
    "error.type"})

(defn- bounded-attributes? [telemetry]
  (every? bounded-attribute-keys (keys (:attributes telemetry))))

(deftest closed-operations-produce-bounded-spans-and-duration-points
  (with-memory-sdk
    (fn [exporter handle]
      (let [cases [[:durable/acquire {:status :acquired} "acquire"]
                   [:durable/publish-wal {:status :published} "publish"]
                   [:durable/publish-checkpoint {:status :reconciled}
                    "checkpoint-publish"]
                   [:durable/commit-reference {:status :committed}
                    "commit-attempt"]
                   [:durable/renew {:status :committed} "renew-attempt"]
                   [:durable/release {:status :committed} "release-attempt"]]
            private-args [{:secret-key "private-credential"}
                          {:owner "private-owner"}
                          (.getBytes "private-payload" "UTF-8")]]
        (doseq [[operation result _] cases]
          (is (identical?
               result
               (observability/around-control
                (join-point operation) private-args (fn [] result)))))
        (is (sdk/force-flush! handle))
        (let [spans (memory/spans exporter)
              durations (filter #(= "jolt.durable.operation.duration" (:name %))
                                (memory/metrics exporter))
              points (:data-points (first durations))
              serialized (pr-str [spans durations])]
          (is (= (mapv #(nth % 2) cases)
                 (mapv #(attribute % :jolt.durable.operation.name) spans)))
          (is (every? #(= :internal (:kind %)) spans))
          (is (every? bounded-attributes? spans))
          (is (= 1 (count durations)))
          (is (= 6 (count points)))
          (is (every? bounded-attributes? points))
          (is (= #{"success" "reconciled"}
                 (set (map #(attribute % :jolt.durable.operation.outcome)
                           points))))
          (is (= "ambiguous"
                 (attribute (first (filter #(= "reconciled"
                                              (attribute % :jolt.durable.operation.outcome))
                                          points))
                            :jolt.durable.failure.category)))
          (doseq [secret ["private-credential" "private-owner" "private-payload"]]
            (is (not (.contains serialized secret)))))))))

(deftest fenced-and-ambiguous-errors-use-closed-categories
  (with-memory-sdk
    (fn [exporter handle]
      (doseq [[type category]
              [[:jdbc.chdb.durable.control/lease-fenced "fenced"]
               [:jdbc.chdb.durable.control/commit-ambiguous "ambiguous"]]]
        (let [failure (ex-info "private exception message"
                               {:type type :credential "private-error-data"})
              observed
              (try
                (observability/around-control
                 (join-point :durable/commit-reference)
                 [{:private "private-argument"}]
                 (fn [] (throw failure)))
                (catch Throwable error error))]
          (is (identical? failure observed))
          (is (= category
                 (attribute (last (memory/spans exporter))
                            :jolt.durable.failure.category)))))
      (is (sdk/force-flush! handle))
      (let [spans (memory/spans exporter)
            metric-points (mapcat :data-points (memory/metrics exporter))
            serialized (pr-str [spans metric-points])]
        (is (every? bounded-attributes? spans))
        (is (every? bounded-attributes? metric-points))
        (is (= #{"fenced" "ambiguous"}
               (set (map #(attribute % :jolt.durable.failure.category)
                         spans))))
        (doseq [secret ["private exception message" "private-error-data"
                        "private-argument"]]
          (is (not (.contains serialized secret))))))))

(deftest suppression-bypasses-observation-before-any-inspection
  (with-memory-sdk
    (fn [exporter _handle]
      (let [result (Object.)]
        (with-redefs [host/wall-nanos
                      (fn [] (throw (ex-info "clock must not run" {})))]
          (is (identical?
               result
               (context/with-instrumentation-suppressed
                 (observability/around-control
                  (join-point :durable/publish-wal)
                  [{:credential "private"}]
                  (fn [] result))))))
        (is (empty? (memory/spans exporter)))))))

(deftest telemetry-failures-do-not-change-application-outcomes
  (let [result (Object.)
        failure (ex-info "application failure" {:private true})]
    (testing "observation setup fails before proceed"
      (let [calls (atom 0)]
        (with-redefs [host/wall-nanos
                      (fn [] (throw (ex-info "telemetry setup failed" {})))]
          (is (identical?
               result
               (observability/around-control
                (join-point :durable/acquire) []
                (fn [] (swap! calls inc) result))))
          (is (= 1 @calls)))))
    (testing "observation and finalization preserve result and Throwable identity"
      (with-memory-sdk
        (fn [_exporter _handle]
          (with-redefs [trace/set-attribute!
                        (fn [& _] (throw (ex-info "attribute failed" {})))
                        trace/set-status!
                        (fn [& _] (throw (ex-info "status failed" {})))
                        trace/end!
                        (fn [& _] (throw (ex-info "end failed" {})))
                        metrics/record!
                        (fn [& _] (throw (ex-info "metric failed" {})))]
            (is (identical?
                 result
                 (observability/around-control
                  (join-point :durable/renew) [] (fn [] result))))
            (is (identical?
                 failure
                 (try
                   (observability/around-control
                    (join-point :durable/renew) [] (fn [] (throw failure)))
                   (catch Throwable error error))))))))))

(deftest provider-contract-is-exact
  (is (= {:schema 1
          :libraries {'io.github.chucklehead-dev/jolt-chdb
                      observability/durable-seam-revision}
          :roles {:durable/control
                  {:fn 'oscope.durable-observability/around-control
                   :contract :args-v1}}}
         observability/aspect-provider)))
