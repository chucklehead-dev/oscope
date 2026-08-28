(ns oscope.telemetry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [oscope.telemetry :as telemetry]))

(deftest trace-selections-are-bounded-and-parameterized
  (let [selection (telemetry/normalize-selection
                   {:service (apply str (repeat 500 "s"))
                    :operation "GET /users"
                    :status "error"
                    :min-duration-ms "12.5"
                    :window "24h"
                    :sql "DROP TABLE otel_traces"})
        query (telemetry/trace-query selection 2000000000000000000)]
    (is (= 100 (count (:service selection))))
    (is (= "GET /users" (:operation selection)))
    (is (= "error" (:status selection)))
    (is (str/includes? (first query) "GROUP BY TraceId HAVING"))
    (is (not (str/includes? (first query) "GET /users")))
    (is (= "GET /users" (nth query 3)))
    (is (= 12500000 (last query)))))

(deftest trace-tree-keeps-orphans-and-bounds-cycles
  (let [tree (telemetry/span-tree
              [{:spanId "root" :parentSpanId "" :name "root"}
               {:spanId "child" :parentSpanId "root" :name "child"}
               {:spanId "orphan" :parentSpanId "missing" :name "orphan"}
               {:spanId "a" :parentSpanId "b" :name "cycle-a"}
               {:spanId "b" :parentSpanId "a" :name "cycle-b"}])]
    (is (= ["root" "orphan" "a"] (mapv :spanId tree)))
    (is (= ["child"] (mapv :spanId (:children (first tree)))))
    (is (= ["b"] (mapv :spanId (:children (last tree)))))
    (is (empty? (:children (first (:children (last tree))))))))

(deftest trace-detail-correlates-logs-and-preserves-events
  (let [calls (atom [])]
    (with-redefs
      [jdbc/fetch
       (fn [_ query]
         (swap! calls conj query)
         (if (str/includes? (first query) "FROM otel_traces")
           [{:traceid "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
             :spanid "1111111111111111" :parentspanid ""
             :servicename "api" :spanname "GET /" :spankind "Server"
             :duration 10 :statuscode "Ok" :eventsjson
             "[{\"name\":\"request.ready\",\"attributes\":{\"rows\":2}}]"}]
           [{:traceid "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
             :spanid "1111111111111111" :severitytext "INFO"
             :servicename "api" :body "ready"}]))]
      (let [trace (telemetry/query-trace
                   ::connection "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
        (is (= "request.ready" (get-in trace [:spans 0 :events 0 :name])))
        (is (= "ready" (get-in trace [:logs 0 :body])))
        (is (= [(second (first @calls)) (second (second @calls))]
               ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]))
        (is (every? #(not (str/includes? (first %) "aaaaaaaa")) @calls))))))

(deftest invalid-trace-identifiers-never-reach-the-database
  (let [calls (atom 0)]
    (with-redefs [jdbc/fetch (fn [& _] (swap! calls inc))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (telemetry/query-trace ::connection "../../etc")))
      (is (zero? @calls)))))

(deftest raw-event-queries-are-bounded-closed-and-parameterized
  (let [now 2000000000000000000
        logs (telemetry/event-query
              {:signal :logs :service "api'; DROP TABLE otel_logs; --"
               :search "timeout" :severity "ERROR" :window "15m" :limit 17}
              now)
        metrics (telemetry/event-query
                 {:signal :metrics :service "worker" :search "queue"
                  :metric-kind "histogram" :window "6h" :limit 9}
                 now)]
    (is (str/includes? (first logs) "FROM otel_logs WHERE"))
    (is (not (str/includes? (first logs) "DROP TABLE")))
    (is (= [(- now (* 15 60 1000000000))
            "api'; DROP TABLE otel_logs; --" "timeout" "ERROR" 17]
           (subvec logs 1)))
    (is (str/includes? (first metrics) "FROM (SELECT TimeUnix"))
    (is (str/includes? (first metrics) "UNION ALL"))
    (is (= [(- now (* 6 60 60 1000000000))
            "worker" "queue" "histogram" 9]
           (subvec metrics 1)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (telemetry/normalize-event-selection
                  {:signal :spans :limit 10})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (telemetry/normalize-event-selection
                  {:signal :logs :limit 10 :sql "select 1"})))))

(deftest raw-event-results-have-one-serializable-shape-per-signal
  (with-redefs
    [jdbc/fetch
     (fn [_ query]
       (if (str/includes? (first query) "otel_logs")
         [{:timestamp "2026-01-01" :severitytext "ERROR" :servicename "api"
           :body "timeout" :traceid "aa" :spanid "bb"}]
         [{:timestamp "2026-01-01" :metrickind "histogram"
           :servicename "worker" :metricname "queue.duration"
           :metricunit "ms" :value 42.0 :count 3 :sum 42.0}]))]
    (is (= [{:timestamp "2026-01-01" :severity "ERROR" :service "api"
             :body "timeout" :traceId "aa" :spanId "bb"}]
           (telemetry/query-events
            ::connection {:signal :logs :limit 10} 2000000000000000000)))
    (is (= [{:timestamp "2026-01-01" :kind "histogram" :service "worker"
             :name "queue.duration" :unit "ms" :value 42.0
             :count 3 :sum 42.0}]
           (telemetry/query-events
            ::connection {:signal :metrics :limit 10} 2000000000000000000)))))
