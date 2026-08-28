(ns oscope.chdb-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [oscope.live :as live]
            [oscope.raw-export :as raw-export]
            [oscope.ui.web :as web]))

(def test-now 1700000001000000000)
(deftest owned-live-source-migrates-queries-and-closes
  (let [source (live/open! {:db-spec "chdb::memory:"
                            :now-fn (constantly test-now)})]
    (try
      (is (:owned? source))
      (is (= :empty (get-in source [:screen :status])))
      (is (= [1 2 3 4]
             (mapv :version
                   (jdbc/fetch (:connection source)
                               "select Version as version from otel_schema_migrations order by Version"))))
      (doseq [service ["api" "api" "worker"]]
        (jdbc/execute!
         (:connection source)
         ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName)
             VALUES (fromUnixTimestamp64Nano(?), ?, 'live.work')"
          (- test-now 1000000) service]))
      (let [screen ((:load-command source) :after-insert
                    {:signal :spans :field :service-name :window :15m :limit 10})]
        (is (= [{:value "api" :count 2} {:value "worker" :count 1}]
               (get-in screen [:table :rows]))))
      (finally (live/close! source)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is closed"
                          ((:loader source) (:selection (:screen source)))))))

(deftest shared-real-connection-remains-usable
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (let [source (live/open! {:connection connection
                              :now-fn (constantly test-now)})]
      (live/close! source)
      (is (= [{:answer 1}] (jdbc/fetch connection "select 1 as answer"))))))

(defn- prefix [bytes length]
  (mapv #(bit-and % 255) (take length bytes)))

(deftest raw-exports-cover-physical-signals-and-own-bytes-after-close
  (let [source (live/open! {:db-spec "chdb::memory:"
                            :now-fn (constantly test-now)})
        connection (:connection source)
        start (- test-now 2000000000)
        end test-now]
    (jdbc/execute!
     connection
     ["INSERT INTO otel_traces (Timestamp, TraceId, SpanId, ServiceName, SpanName)
         VALUES (fromUnixTimestamp64Nano(?), 'trace-export', 'span-export',
                 'export-test', 'export.span')"
      (- test-now 1000000)])
    (jdbc/execute!
     connection
     ["INSERT INTO otel_logs (Timestamp, TraceId, SpanId, ServiceName, Body)
         VALUES (fromUnixTimestamp64Nano(?), 'trace-export', 'span-export',
                 'export-test', 'export log')"
      (- test-now 1000000)])
    (doseq [[table extra-columns extra-values]
            [["otel_metrics_gauge" ", Value" ", 1.5"]
             ["otel_metrics_sum"
              ", Value, AggregationTemporality, IsMonotonic" ", 2.5, 2, true"]
             ["otel_metrics_histogram"
              ", Count, Sum, BucketCounts, ExplicitBounds, Min, Max, AggregationTemporality"
              ", 2, 3.0, [1,1], [2.0], 1.0, 2.0, 2"]]]
      (jdbc/execute!
       connection
       (str "INSERT INTO " table
            " (ServiceName, MetricName, StartTimeUnix, TimeUnix" extra-columns ") "
            "VALUES ('export-test', 'export.metric', fromUnixTimestamp(1700000000), "
            "fromUnixTimestamp(1700000000)" extra-values ")")))
    (let [base {:start-unix-nano start :end-unix-nano end
                :format :parquet :max-rows 10 :max-bytes (* 4 1024 1024)}
          results
          (mapv (fn [[signal kind]]
                  ((:export-command source) [signal kind]
                   (assoc base :signal signal :metric-kind kind)))
                [[:spans nil] [:logs nil] [:metrics :gauge]
                 [:metrics :sum] [:metrics :histogram]])
          arrow ((:export-command source) :arrow
                 (assoc base :signal :spans :metric-kind nil :format :arrow))
          ring-response
          ((web/handler source {:path "/embedded/oscope"})
           {:request-method :get :uri "/embedded/oscope/export"
            :query-params
            {"signal" "metrics" "metric-kind" "histogram"
             "start-unix-nano" (str start) "end-unix-nano" (str end)
             "format" "parquet" "max-rows" "10"
             "max-bytes" (str (* 4 1024 1024))}})
          ring-body (:body ring-response)
          retained (mapv :bytes (conj results arrow))]
      (is (= 5 (count results)))
      (doseq [result results]
        (is (= [80 65 82 49] (prefix (:bytes result) 4)))
        (is (<= (:byte-count result) (* 4 1024 1024))))
      (is (= [65 82 82 79 87 49] (prefix (:bytes arrow) 6)))
      (is (= 200 (:status ring-response)))
      (is (= "application/vnd.apache.parquet"
             (get-in ring-response [:headers "Content-Type"])))
      (is (re-matches #"attachment; filename=\"oscope-metrics-histogram-[0-9]+-[0-9]+\.parquet\""
                      (get-in ring-response [:headers "Content-Disposition"])))
      (is (= (str (count (:bytes ring-body)))
             (get-in ring-response [:headers "Content-Length"])))
      ;; This direct Ring test does not give the body to jolt-http, so it owns
      ;; the explicit release that the real wire integration performs for us.
      (web/release-export-body! ring-body)
      (live/close! source)
      ;; Every prefix remains readable after the native result and owned source
      ;; have been destroyed. Only Jolt-owned byte arrays crossed query-bytes.
      (doseq [bytes (butlast retained)]
        (is (= [80 65 82 49] (prefix bytes 4))))
      (is (= [65 82 82 79 87 49] (prefix (last retained) 6))))))
