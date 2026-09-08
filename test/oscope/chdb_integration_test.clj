(ns oscope.chdb-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [oscope.live :as live]
            [oscope.query.expression :as query-expression]
            [oscope.query.expression.chdb :as query-expression-chdb]
            [oscope.raw-export :as raw-export]
            [oscope.ui.native :as native]
            [oscope.ui.web :as web]
            [otel.exporter.chdb.schema :as schema]))

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
      (doseq [[service value] [["api" 2.0] ["api" 4.0] ["worker" 10.0]]]
        (jdbc/execute!
         (:connection source)
         ["INSERT INTO otel_metrics_gauge
             (TimeUnix, ServiceName, MetricName, MetricUnit, ScopeName, Value)
           VALUES (fromUnixTimestamp(?), ?, 'queue.depth', '{job}',
                   'demo.metrics', ?)"
          1700000000 service value]))
      (let [screen ((:load-command source) :metric-series
                    {:mode :metric-series :metric-kind :gauge
                     :metric-name "queue.depth" :group-by [:service-name]
                     :bucket :5m :aggregates [:count :sum :avg]
                     :window :15m :limit 10})]
        (is (= :telemetry-metric-series (:view screen)))
        (is (= [{:bucket-start-unix-nano 1699999800000000000
                 :service-name "api" :count 2 :sum 6.0 :avg 3.0}
                {:bucket-start-unix-nano 1699999800000000000
                 :service-name "worker" :count 1 :sum 10.0 :avg 10.0}]
               (get-in screen [:table :rows])))
        (is (= [:count]
               (mapv :y (get-in screen [:chart :layers])))))
      (finally (live/close! source)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is closed"
                          ((:loader source) (:selection (:screen source)))))))

(deftest shared-real-connection-remains-usable
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (let [source (live/open! {:connection connection
                              :now-fn (constantly test-now)})]
      (live/close! source)
      (is (= [{:answer 1}] (jdbc/fetch connection "select 1 as answer"))))))

(deftest telemetry-expression-runs-bounded-aggregates-in-real-chdb
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (schema/migrate! connection)
    (doseq [[service duration status]
            [["api" 10 "OK"] ["api" 30 "OK"] ["worker" 100 "ERROR"]]]
      (jdbc/execute!
       connection
       ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName, Duration, StatusCode)
           VALUES (fromUnixTimestamp64Nano(?), ?, 'aggregate.work', ?, ?)"
        (- test-now 1000000) service duration status]))
    (let [rows
          (query-expression-chdb/execute!
           connection
           {:signal :spans :window :15m :group-by [:service-name] :filters []
            :series [{:as :n :op :count}
                     {:as :total :op :sum :field :duration-ns}
                     {:as :mean :op :avg :field :duration-ns}
                     {:as :low :op :min :field :duration-ns}
                     {:as :high :op :max :field :duration-ns}
                     {:as :p50 :op :percentile :field :duration-ns :percentile 50}
                     {:as :p99 :op :percentile :field :duration-ns :percentile 99}]
            :limit 10}
           test-now)
          api (first (filter #(= "api" (:service-name %)) rows))
          worker (first (filter #(= "worker" (:service-name %)) rows))]
      (is (= {:n 2 :total 40 :mean 20 :low 10 :high 30 :p50 30 :p99 30}
             (dissoc api :service-name)))
      (is (= {:n 1 :total 100 :mean 100 :low 100 :high 100
              :p50 100 :p99 100}
             (dissoc worker :service-name))))
    (doseq [[table columns values]
            [["otel_metrics_gauge" ", Value" ", 2.0"]
             ["otel_metrics_sum" ", Value" ", 3.0"]
             ["otel_metrics_histogram" ", Count, Sum, Min, Max"
              ", 2, 10.0, 4.0, 6.0"]]]
      (jdbc/execute!
       connection
       (str "INSERT INTO " table
            " (ServiceName, MetricName, StartTimeUnix, TimeUnix" columns ") "
            "VALUES ('api', 'work.duration', fromUnixTimestamp(1700000000), "
            "fromUnixTimestamp(1700000000)" values ")")))
    (let [rows
          (query-expression-chdb/execute!
           connection
           {:signal :metrics :window :15m :group-by [:metric-name] :filters []
            :series [{:as :points :op :count}
                     {:as :total-value :op :sum :field :value}
                     {:as :p95-value :op :percentile :field :value
                      :percentile 95}]
            :limit 10}
           test-now)]
      (is (= [{:metric-name "work.duration" :points 1
               :total-value 2 :p95-value 2}]
             rows)
          "reusable metrics aggregate gauges without mixing sum/histogram provenance"))
    (let [prefix (apply str (repeat 160 "x"))]
      (doseq [suffix ["a" "b"]]
        (jdbc/execute!
         connection
         ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName, Duration)
             VALUES (fromUnixTimestamp64Nano(?), ?, 'long-name', 1)"
          (- test-now 1000000) (str prefix suffix)]))
      (let [rows (query-expression-chdb/execute!
                  connection
                  {:signal :spans :window :15m :group-by [:service-name]
                   :filters [{:field :span-name :op :eq :value "long-name"}]
                   :series [{:as :n :op :count}] :limit 10}
                  test-now)]
        (is (= 2 (count rows))
            "display truncation must not merge distinct group identities")
        (is (= [1 1] (sort (map :n rows))))))))

(deftest native-adapter-renders-a-real-chdb-query-as-canonical-svg
  (let [source (live/open! {:db-spec "chdb::memory:"
                            :now-fn (constantly test-now)})
        instance* (atom nil)]
    (try
      (jdbc/execute!
       (:connection source)
       ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName)
           VALUES (fromUnixTimestamp64Nano(?), 'native-visible', 'native.work')"
        (- test-now 1000000)])
      (let [selection {:signal :spans :field :service-name
                       :window :15m :limit 10}
            screen ((:load-command source) :native-visible selection)
            instance (native/create-instance {:screen screen
                                              :loader (:loader source)})
            _ (reset! instance* instance)
            hiccup ((:view instance) screen)
            chart-path (first @(get-in instance [:chart-store :paths]))
            svg (String. (java.nio.file.Files/readAllBytes chart-path)
                         java.nio.charset.StandardCharsets/UTF_8)]
        (is (= [{:value "native-visible" :count 1}]
               (get-in screen [:table :rows])))
        (is (some? chart-path))
        (is (.contains svg "native-visible"))
        (is (.contains (pr-str hiccup) ":picture")))
      (finally
        (when-let [instance @instance*] ((:close! instance)))
        (live/close! source)))))

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
