(ns oscope.raw-export
  "Closed, bounded Arrow and Parquet exports of physical telemetry rows."
  (:require [jdbc.chdb :as chdb]))

(def max-time-range-nanos (* 24 60 60 1000000000))
(def max-result-rows 100000)
(def max-result-bytes (* 64 1024 1024))
(def default-max-rows 10000)
(def default-max-bytes (* 16 1024 1024))

(def ^:private max-unix-nano 9223372036854775807)
(def ^:private selection-keys
  #{:signal :metric-kind :start-unix-nano :end-unix-nano
    :format :max-rows :max-bytes})
(def ^:private result-keys
  #{:oscope.export/version :selection :content-type :suggested-filename
    :byte-count :bytes})
(def ^:private format-metadata
  {:arrow {:content-type "application/vnd.apache.arrow.file"
           :extension "arrow"}
   :parquet {:content-type "application/vnd.apache.parquet"
             :extension "parquet"}})
(def ^:private sources
  {[:spans nil]
   {:table "otel_traces"
    :time-expression "toUnixTimestamp64Nano(Timestamp)"
    :order-expression "Timestamp, TraceId, SpanId"}
   [:logs nil]
   {:table "otel_logs"
    :time-expression "toUnixTimestamp64Nano(Timestamp)"
    :order-expression "Timestamp, TraceId, SpanId"}
   [:metrics :gauge]
   {:table "otel_metrics_gauge"
    :time-expression "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000"
    :order-expression "TimeUnix, ServiceName, MetricName"}
   [:metrics :sum]
   {:table "otel_metrics_sum"
    :time-expression "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000"
    :order-expression "TimeUnix, ServiceName, MetricName"}
   [:metrics :histogram]
   {:table "otel_metrics_histogram"
    :time-expression "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000"
    :order-expression "TimeUnix, ServiceName, MetricName"}})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :oscope.export/error true :type type))))

(defn normalize-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection "oscope export selection must be a map"
           {:selection selection}))
  (when-let [unknown (seq (remove selection-keys (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope export selection contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [{:keys [signal metric-kind start-unix-nano end-unix-nano
                format max-rows max-bytes]}
        (merge {:format :parquet :max-rows default-max-rows
                :max-bytes default-max-bytes}
               selection)
        metric-kind (when (= signal :metrics) metric-kind)]
    (when-not (contains? sources [signal metric-kind])
      (fail! ::unsupported-source "oscope export source is not supported"
             {:signal signal :metric-kind metric-kind}))
    (when (and (not= signal :metrics) (some? (:metric-kind selection)))
      (fail! ::unexpected-metric-kind
             "metric kind is only valid for metrics exports"
             {:signal signal :metric-kind (:metric-kind selection)}))
    (when-not (and (integer? start-unix-nano)
                   (integer? end-unix-nano)
                   (<= 0 start-unix-nano max-unix-nano)
                   (<= 1 end-unix-nano max-unix-nano)
                   (< start-unix-nano end-unix-nano)
                   (<= (- end-unix-nano start-unix-nano)
                       max-time-range-nanos))
      (fail! ::invalid-window
             "oscope export requires an absolute half-open window of at most 24 hours"
             {:start-unix-nano start-unix-nano
              :end-unix-nano end-unix-nano
              :maximum-range-nanos max-time-range-nanos}))
    (when-not (contains? format-metadata format)
      (fail! ::unsupported-format "oscope export format is not supported"
             {:format format :supported-formats (vec (keys format-metadata))}))
    (when-not (and (integer? max-rows) (<= 1 max-rows max-result-rows))
      (fail! ::invalid-row-cap "oscope export row cap is outside the hard limit"
             {:max-rows max-rows :maximum max-result-rows}))
    (when-not (and (integer? max-bytes) (<= 1 max-bytes max-result-bytes))
      (fail! ::invalid-byte-cap "oscope export byte cap is outside the hard limit"
             {:max-bytes max-bytes :maximum max-result-bytes}))
    {:signal signal :metric-kind metric-kind
     :start-unix-nano start-unix-nano :end-unix-nano end-unix-nano
     :format format :max-rows max-rows :max-bytes max-bytes}))

(defn compile-query [selection]
  (let [{:keys [signal metric-kind start-unix-nano end-unix-nano max-rows]
         :as selected} (normalize-selection selection)
        {:keys [table time-expression order-expression]}
        (get sources [signal metric-kind])]
    {:oscope.export/version 1
     :selection selected
     :query [(str "SELECT * FROM " table
                  " WHERE " time-expression " >= ?"
                  " AND " time-expression " < ?"
                  " ORDER BY " order-expression " LIMIT ?")
             start-unix-nano end-unix-nano max-rows]}))

(defn validate-plan [plan]
  (when-not (and (map? plan) (= 1 (:oscope.export/version plan)))
    (fail! ::invalid-plan "oscope export plan version is not supported"
           {:plan plan}))
  (let [expected (compile-query (:selection plan))]
    (when-not (= expected plan)
      (fail! ::invalid-plan "oscope export plan does not match its selection"
             {:plan plan}))
    plan))

(defn suggested-filename [selection]
  (let [{:keys [signal metric-kind start-unix-nano end-unix-nano format]
         :as selection} (normalize-selection selection)
        source-name (if (= :metrics signal)
                      (str "metrics-" (name metric-kind)) (name signal))]
    (str "oscope-" source-name "-" start-unix-nano "-" end-unix-nano
         "." (get-in format-metadata [format :extension]))))

(defn response-metadata [selection]
  (let [{:keys [format] :as selection} (normalize-selection selection)]
    {:content-type (get-in format-metadata [format :content-type])
     :suggested-filename (suggested-filename selection)}))

(defn validate-result
  "Validate the complete owned encoded-result envelope. This is intentionally
  closed: adapters must not trust exporter-supplied response metadata or
  accept driver-specific fields through the shared command/effect boundary."
  [result]
  (when-not (map? result)
    (fail! ::invalid-result "oscope export result must be a map"
           {:result result}))
  (when-let [unknown (seq (remove result-keys (keys result)))]
    (fail! ::unsupported-result-key
           "oscope export result contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [selection (normalize-selection (:selection result))
        expected-metadata (response-metadata selection)
        byte-count (:byte-count result)
        bytes (:bytes result)]
    (when-not (and (= 1 (:oscope.export/version result))
                   (= selection (:selection result))
                   (= expected-metadata
                      (select-keys result [:content-type :suggested-filename]))
                   (integer? byte-count)
                   (<= 0 byte-count (:max-bytes selection))
                   (bytes? bytes)
                   (= byte-count (count bytes)))
      (fail! ::invalid-result "oscope export result is invalid"
             {:selection selection :byte-count byte-count}))
    result))

(defn execute! [connection selection]
  (let [{:keys [selection query]} (compile-query selection)
        {:keys [format max-rows max-bytes]} selection
        result (chdb/query-bytes connection query
                                 {:format format :max-rows max-rows
                                  :max-bytes max-bytes})]
    (when-not (and (= format (:format result))
                   (integer? (:byte-count result))
                   (<= 0 (:byte-count result) max-bytes)
                   (bytes? (:bytes result))
                   (= (:byte-count result) (count (:bytes result))))
      (fail! ::invalid-driver-result
             "jolt-chdb returned an invalid encoded export"
             {:format (:format result) :byte-count (:byte-count result)}))
    (validate-result
     (merge {:oscope.export/version 1 :selection selection
             :byte-count (:byte-count result) :bytes (:bytes result)}
            (response-metadata selection)))))
