(ns oscope.query.expression
  "Closed, bounded telemetry query expressions used by reusable Plotje charts."
  (:require [clojure.string :as str]
            [oscope.query :as query]))

(def percentile-presets #{50 75 90 95 99})
(def aggregate-ops #{:count :sum :avg :min :max :percentile})
(def max-groups 2)
(def max-filters 4)
(def max-series 8)
(def max-group-text-length 160)
(def max-source-rows 100000)
(def max-source-bytes 67108864)
(def max-query-memory-bytes 134217728)
(def max-query-seconds 5)

(def ^:private signals
  {:spans
   {:source "otel_traces"
    :time "toUnixTimestamp64Nano(Timestamp)"
    :dimensions {:service-name "ServiceName" :span-name "SpanName"
                 :span-kind "SpanKind" :status-code "StatusCode"
                 :scope-name "ScopeName"
                 :http-request-method "SpanAttributes['http.request.method']"
                 :http-response-status-code "SpanAttributes['http.response.status_code']"
                 :deployment-environment
                 "ResourceAttributes['deployment.environment.name']"}
    :numbers {:duration-ns "Duration"}}
   :logs
   {:source "otel_logs"
    :time "toUnixTimestamp64Nano(Timestamp)"
    :dimensions {:service-name "ServiceName" :severity-text "SeverityText"
                 :event-name "EventName" :scope-name "ScopeName"
                 :deployment-environment
                 "ResourceAttributes['deployment.environment.name']"}
   :numbers {:severity-number "SeverityNumber"}}
   :metrics
   ;; Reusable expressions intentionally start with gauge samples. Sum and
   ;; histogram rows require temporality/reset and optional-extrema semantics;
   ;; the existing kind-aware metric-series path owns those until the reusable
   ;; grammar can retain that provenance.
   {:source "otel_metrics_gauge"
    :time "toInt64(toUnixTimestamp(TimeUnix)) * 1000000000"
    :dimensions {:service-name "ServiceName" :metric-name "MetricName"
                 :metric-unit "MetricUnit" :scope-name "ScopeName"
                 :deployment-environment
                 "ResourceAttributes['deployment.environment.name']"}
    :numbers {:value "Value"}}})

(def ^:private expression-keys
  #{:signal :window :group-by :filters :series :limit})
(def ^:private filter-keys #{:field :op :value})
(def ^:private series-keys #{:as :op :field :percentile})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :oscope.query-expression/error true
                                 :type type))))
(defn- unknown! [where allowed value]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! ::unsupported-key
           (str where " contains unsupported keys: "
                (str/join ", " (map pr-str (sort-by str unknown))))
           {:where where :keys (vec unknown)})))
(defn- alias! [value]
  (when-not (and (keyword? value) (nil? (namespace value))
                 (<= 1 (count (name value)) 64))
    (fail! ::invalid-alias "series aliases must be short unqualified keywords"
           {:alias value}))
  value)
(defn- config! [signal]
  (or (get signals signal)
      (fail! ::unsupported-signal "telemetry query signal is unsupported"
             {:signal signal :supported-signals (vec (keys signals))})))

(defn validate-expression [value]
  (when-not (map? value)
    (fail! ::invalid-expression "telemetry query must be a map" {:query value}))
  (unknown! "telemetry query" expression-keys value)
  (let [{:keys [signal window group-by filters series limit]} value
        config (config! signal)]
    (when-not (contains? query/windows window)
      (fail! ::unsupported-window "telemetry query window is unsupported"
             {:window window :supported-windows (vec (keys query/windows))}))
    (when-not (and (vector? group-by) (<= 0 (count group-by) max-groups)
                   (= (count group-by) (count (distinct group-by))))
      (fail! ::invalid-grouping "group-by must contain up to 2 distinct fields"
             {:group-by group-by}))
    (doseq [field group-by]
      (when-not (contains? (:dimensions config) field)
        (fail! ::unsupported-dimension "group-by field is unsupported"
               {:field field :supported-fields (vec (keys (:dimensions config)))})))
    (when-not (and (vector? filters) (<= (count filters) max-filters))
      (fail! ::invalid-filters "filters must contain up to 4 entries"
             {:filters filters}))
    (let [filters
          (mapv
           (fn [filter]
             (when-not (map? filter)
               (fail! ::invalid-filter "every filter must be a map" {:filter filter}))
             (unknown! "telemetry filter" filter-keys filter)
             (let [{:keys [field op value]} filter]
               (when-not (contains? (:dimensions config) field)
                 (fail! ::unsupported-filter-field "filter field is unsupported"
                        {:field field :supported-fields
                         (vec (keys (:dimensions config)))}))
               (when-not (= :eq op)
                 (fail! ::unsupported-filter-op "filter op must be :eq" {:op op}))
               (when-not (and (string? value) (<= 1 (count value) query/max-value-length))
                 (fail! ::invalid-filter-value
                        "filter value must be a non-empty bounded string" {:value value}))
               {:field field :op op :value value}))
           filters)]
      (when-not (and (vector? series) (<= 1 (count series) max-series))
        (fail! ::invalid-series "series must contain from 1 to 8 entries"
               {:series series}))
      (let [series
            (mapv
             (fn [item]
               (when-not (map? item)
                 (fail! ::invalid-series "every series must be a map" {:series item}))
               (unknown! "telemetry series" series-keys item)
               (let [{alias :as op :op field :field percentile :percentile} item]
                 (alias! alias)
                 (when-not (contains? aggregate-ops op)
                   (fail! ::unsupported-aggregate "series aggregate is unsupported"
                          {:op op :supported-ops (vec (sort aggregate-ops))}))
                 (if (= :count op)
                   (when (or field percentile)
                     (fail! ::invalid-count "count series does not accept field or percentile"
                            {:series item}))
                   (do
                     (when-not (contains? (:numbers config) field)
                       (fail! ::unsupported-numeric-field
                              "aggregate numeric field is unsupported"
                              {:field field :supported-fields
                               (vec (keys (:numbers config)))}))
                     (if (= :percentile op)
                       (when-not (contains? percentile-presets percentile)
                         (fail! ::unsupported-percentile
                                "percentile must be p50, p75, p90, p95, or p99"
                                {:percentile percentile
                                 :supported-percentiles
                                 (vec (sort percentile-presets))}))
                       (when percentile
                         (fail! ::unexpected-percentile
                                "percentile is valid only for :percentile series"
                                {:series item})))))
                 (cond-> {:as alias :op op}
                   field (assoc :field field)
                   percentile (assoc :percentile percentile))))
             series)
            aliases (mapv :as series)]
        (when-not (= (count aliases) (count (distinct aliases)))
          (fail! ::duplicate-alias "series aliases must be unique" {:aliases aliases}))
        (when (some (set group-by) aliases)
          (fail! ::alias-collision "series aliases must not replace group-by fields"
                 {:group-by group-by :aliases aliases}))
        (when-not (and (integer? limit) (<= 1 limit query/max-result-limit))
          (fail! ::invalid-limit "telemetry query limit is outside the explorer cap"
                 {:limit limit :minimum 1 :maximum query/max-result-limit}))
        {:signal signal :window window :group-by group-by :filters filters
         :series series :limit limit}))))

(defn from-selection [{:keys [signal field window limit]}]
  (validate-expression
   {:signal signal :window window :group-by [field] :filters []
    :series [{:as :count :op :count}] :limit limit}))

(defn output-fields [expression]
  (let [{:keys [group-by series]} (validate-expression expression)]
    (vec (concat group-by (map :as series)))))

(defn- aggregate-sql [config {:keys [op field percentile]}]
  (let [number (get-in config [:numbers field])]
    (case op
      :count "count()"
      :sum (str "sum(" number ")")
      :avg (str "avg(" number ")")
      :min (str "min(" number ")")
      :max (str "max(" number ")")
      :percentile (str "quantileExact(" (/ percentile 100.0) ")(" number ")"))))

(defn compile-query [expression now-unix-nano]
  (when-not (and (integer? now-unix-nano) (<= 1 now-unix-nano 9223372036854775807))
    (fail! ::invalid-time "telemetry query end must be epoch nanoseconds"
           {:end-unix-nano now-unix-nano}))
  (let [{:keys [signal window group-by filters series limit] :as expression}
        (validate-expression expression)
        config (config! signal)
        start (max 0 (- now-unix-nano (get query/windows window)))
        group-pairs (map-indexed
                     (fn [index field]
                       [(str "leftUTF8(toString(" (get-in config [:dimensions field])
                             "), ?) AS dimension" index)
                        field]) group-by)
        series-pairs (map-indexed
                      (fn [index item]
                        [(str (aggregate-sql config item) " AS series" index)
                         (:as item)]) series)
        selections (concat (map first group-pairs) (map first series-pairs))
        filter-sql (mapv #(str "toString(" (get-in config [:dimensions (:field %)])
                              ") = ?") filters)
        where (concat [(str (:time config) " >= ?")
                       (str (:time config) " < ?")] filter-sql)
        groups (mapv #(str "toString(" (get-in config [:dimensions %]) ")")
                     group-by)
        order-alias "series0"
        sql (str "SELECT " (str/join ", " selections) "\nFROM " (:source config)
                 "\nWHERE " (str/join " AND " where)
                 (when (seq groups) (str "\nGROUP BY " (str/join ", " groups)))
                 "\nORDER BY " order-alias " DESC"
                 (when (seq groups) (str ", " (str/join ", " groups) " ASC"))
                 "\nLIMIT ?"
                 "\nSETTINGS max_rows_to_read = " max-source-rows
                 ", max_bytes_to_read = " max-source-bytes
                 ", max_execution_time = " max-query-seconds
                 ", timeout_overflow_mode = 'throw'"
                 ", max_memory_usage = " max-query-memory-bytes
                 ", max_threads = 1")
        params (vec (concat (repeat (count group-by) max-group-text-length)
                            [start now-unix-nano]
                            (map :value filters) [limit]))]
    {:expression expression :sqlvec (into [sql] params)
     :columns (vec (concat (map-indexed (fn [i field] [(keyword (str "dimension" i)) field]) group-by)
                           (map-indexed (fn [i item] [(keyword (str "series" i)) (:as item)]) series)))
     :limit limit}))
