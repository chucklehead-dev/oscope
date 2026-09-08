(ns oscope.query.expression
  "Closed, bounded telemetry query expressions used by reusable Plotje charts."
  (:require [clojure.string :as str]
            [oscope.query :as query]))

(def percentile-presets #{50 75 90 95 99})
(def bucket-presets
  {:none nil
   :1m (* 60 1000000000)
   :5m (* 5 60 1000000000)
   :15m (* 15 60 1000000000)
   :1h (* 60 60 1000000000)})
(def aggregate-ops #{:count :sum :avg :min :max :percentile})
(def calculation-ops #{:add :subtract :multiply :divide})
(def max-groups 2)
(def max-filters 4)
(def max-series-filters 2)
(def max-total-series-filters 8)
(def max-series 8)
(def max-calculations 6)
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
  #{:signal :window :bucket :group-by :filters :series :calculations :limit})
(def ^:private filter-keys #{:field :op :value})
(def ^:private series-keys #{:as :op :field :percentile :filters})
(def ^:private calculation-keys #{:as :op :args})

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

(defn- finite-number? [value]
  (and (number? value)
       (let [number (double value)]
         (and (= number number) (not= number ##Inf) (not= number ##-Inf)))))

(defn- validate-filter [config filter where]
  (when-not (map? filter)
    (fail! ::invalid-filter (str "every " where " filter must be a map")
           {:filter filter :where where}))
  (unknown! (str "telemetry " where " filter") filter-keys filter)
  (let [{:keys [field op value]} filter]
    (when-not (contains? (:dimensions config) field)
      (fail! ::unsupported-filter-field "filter field is unsupported"
             {:field field :where where
              :supported-fields (vec (keys (:dimensions config)))}))
    (when-not (= :eq op)
      (fail! ::unsupported-filter-op "filter op must be :eq"
             {:op op :where where}))
    (when-not (and (string? value) (<= 1 (count value) query/max-value-length))
      (fail! ::invalid-filter-value
             "filter value must be a non-empty bounded string"
             {:value value :where where}))
    {:field field :op op :value value}))

(defn- validate-series-filters [config filters]
  (when-not (and (vector? filters) (<= (count filters) max-series-filters))
    (fail! ::invalid-series-filters
           "series filters must contain up to 2 entries"
           {:filters filters :maximum max-series-filters}))
  (mapv #(validate-filter config % "series") filters))

(defn- validate-calculations [value series-aliases occupied-aliases]
  (let [calculations (get value :calculations [])]
    (when-not (and (vector? calculations)
                   (<= (count calculations) max-calculations))
      (fail! ::invalid-calculations
             "calculations must contain up to 6 entries"
             {:calculations calculations :maximum max-calculations}))
    (let [available (set series-aliases)
          calculations
          (mapv
           (fn [calculation]
             (when-not (map? calculation)
               (fail! ::invalid-calculation "every calculation must be a map"
                      {:calculation calculation}))
             (unknown! "telemetry calculation" calculation-keys calculation)
             (let [{alias :as op :op args :args} calculation]
               (alias! alias)
               (when-not (contains? calculation-ops op)
                 (fail! ::unsupported-calculation
                        "calculation op must be :add, :subtract, :multiply, or :divide"
                        {:op op :supported-ops (vec (sort calculation-ops))}))
               (when-not (and (vector? args) (= 2 (count args)))
                 (fail! ::invalid-calculation-args
                        "calculation args must contain exactly two operands"
                        {:args args}))
               (doseq [operand args]
                 (when-not (or (and (keyword? operand)
                                    (contains? available operand))
                               (finite-number? operand))
                   (fail! ::invalid-calculation-operand
                          "calculation operands must be aggregate series aliases or finite numbers"
                          {:operand operand :series-aliases series-aliases})))
               {:as alias :op op :args args}))
           calculations)
          aliases (mapv :as calculations)]
      (when-not (= (count aliases) (count (distinct aliases)))
        (fail! ::duplicate-calculation-alias
               "calculation aliases must be unique" {:aliases aliases}))
      (when-let [collision (some occupied-aliases aliases)]
        (fail! ::alias-collision
               "calculation aliases must not replace grouping, bucket, or aggregate series fields"
               {:alias collision :occupied-aliases (vec occupied-aliases)}))
      calculations)))

(defn validate-expression [value]
  (when-not (map? value)
    (fail! ::invalid-expression "telemetry query must be a map" {:query value}))
  (unknown! "telemetry query" expression-keys value)
  (let [{:keys [signal window group-by filters series limit]} value
        bucket (get value :bucket :none)
        config (config! signal)]
    (when-not (contains? query/windows window)
      (fail! ::unsupported-window "telemetry query window is unsupported"
             {:window window :supported-windows (vec (keys query/windows))}))
    (when-not (contains? bucket-presets bucket)
      (fail! ::unsupported-bucket
             "telemetry query bucket must be :none, :1m, :5m, :15m, or :1h"
             {:bucket bucket :supported-buckets (vec (keys bucket-presets))}))
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
          (mapv #(validate-filter config % "global") filters)]
      (when-not (and (vector? series) (<= 1 (count series) max-series))
        (fail! ::invalid-series "series must contain from 1 to 8 entries"
               {:series series}))
      (let [series
            (mapv
             (fn [item]
               (when-not (map? item)
                 (fail! ::invalid-series "every series must be a map" {:series item}))
               (unknown! "telemetry series" series-keys item)
               (let [{alias :as op :op field :field percentile :percentile} item
                     filters (validate-series-filters config (get item :filters []))]
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
                   percentile (assoc :percentile percentile)
                   (seq filters) (assoc :filters filters))))
             series)
            aliases (mapv :as series)]
        (when (> (reduce + (map #(count (:filters %)) series))
                 max-total-series-filters)
          (fail! ::too-many-series-filters
                 "telemetry query must contain at most 8 series filters in total"
                 {:maximum max-total-series-filters}))
        (when-not (= (count aliases) (count (distinct aliases)))
          (fail! ::duplicate-alias "series aliases must be unique" {:aliases aliases}))
        (when-let [collision (some (set (cond-> group-by
                                          (not= :none bucket)
                                          (conj :bucket-start-unix-nano)))
                                   aliases)]
          (fail! ::alias-collision
                 "series aliases must not replace grouping or bucket fields"
                 {:alias collision :group-by group-by :bucket bucket
                  :aliases aliases}))
        (let [calculations (validate-calculations
                            value aliases
                            (set (concat group-by
                                         (when (not= :none bucket)
                                           [:bucket-start-unix-nano])
                                         aliases)))]
          (when-not (and (integer? limit) (<= 1 limit query/max-result-limit))
            (fail! ::invalid-limit "telemetry query limit is outside the explorer cap"
                   {:limit limit :minimum 1 :maximum query/max-result-limit}))
          {:signal signal :window window :bucket bucket
           :group-by group-by :filters filters
           :series series :calculations calculations :limit limit})))))

(defn from-selection [{:keys [signal field window limit]}]
  (validate-expression
   {:signal signal :window window :bucket :none :group-by [field] :filters []
    :series [{:as :count :op :count}] :limit limit}))

(defn output-fields [expression]
  (let [{:keys [bucket group-by series calculations]}
        (validate-expression expression)]
    (vec (concat (when (not= :none bucket) [:bucket-start-unix-nano])
                 group-by (map :as series) (map :as calculations)))))

(defn- operand-value [row operand]
  (if (keyword? operand)
    (do
      (when-not (contains? row operand)
        (fail! ::missing-series-result
               "calculation aggregate result is missing"
               {:operand operand :available-fields (vec (keys row))}))
      (let [value (get row operand)]
        (when-not (or (nil? value) (finite-number? value))
          (fail! ::non-numeric-series-result
                 "calculation aggregate result must be a finite number or nil"
                 {:operand operand :value value}))
        value))
    operand))

(defn- calculate [{:keys [op args]} row]
  (let [left (operand-value row (first args))
        right (operand-value row (second args))]
    (when (and (some? left) (some? right)
               (not (and (= :divide op) (zero? (double right)))))
      (let [left (double left)
            right (double right)
            result (case op
                     :add (+ left right)
                     :subtract (- left right)
                     :multiply (* left right)
                     :divide (/ left right))]
        (when (finite-number? result) result)))))

(defn apply-calculations [row calculations]
  (reduce (fn [result calculation]
            (assoc result (:as calculation) (calculate calculation row)))
          row calculations))

(defn- filter-sql [config filter]
  (str "toString(" (get-in config [:dimensions (:field filter)]) ") = ?"))

(defn- aggregate-sql [config {:keys [op field percentile filters]}]
  (let [number (get-in config [:numbers field])
        condition (when (seq filters)
                    (str/join " AND " (map #(filter-sql config %) filters)))
        suffix (if condition "OrNullIf" "")
        args (fn [value]
               (if condition (str value ", " condition) value))]
    (case op
      :count (if condition (str "countIf(" condition ")") "count()")
      :sum (str "sum" suffix "(" (args number) ")")
      :avg (str "avg" suffix "(" (args number) ")")
      :min (str "min" suffix "(" (args number) ")")
      :max (str "max" suffix "(" (args number) ")")
      :percentile (str "quantileExact" suffix "(" (/ percentile 100.0) ")(" (args number) ")"))))

(defn compile-query [expression now-unix-nano]
  (when-not (and (integer? now-unix-nano) (<= 1 now-unix-nano 9223372036854775807))
    (fail! ::invalid-time "telemetry query end must be epoch nanoseconds"
           {:end-unix-nano now-unix-nano}))
  (let [{:keys [signal window bucket group-by filters series calculations limit]
         :as expression}
        (validate-expression expression)
        config (config! signal)
        bucket-nanos (get bucket-presets bucket)
        start (max 0 (- now-unix-nano (get query/windows window)))
        bucket-pair (when bucket-nanos
                      [(str "intDiv(" (:time config)
                            ", ?) * ? AS bucket0")
                       :bucket-start-unix-nano])
        group-pairs (map-indexed
                     (fn [index field]
                       [(str "leftUTF8(toString(" (get-in config [:dimensions field])
                             "), ?) AS dimension" index)
                        field]) group-by)
        series-pairs (map-indexed
                      (fn [index item]
                        [(str (aggregate-sql config item) " AS series" index)
                         (:as item)]) series)
        selections (concat (when bucket-pair [(first bucket-pair)])
                           (map first group-pairs) (map first series-pairs))
        global-filter-sql (mapv #(filter-sql config %) filters)
        where (concat [(str (:time config) " >= ?")
                       (str (:time config) " < ?")] global-filter-sql)
        groups (concat (when bucket-pair ["bucket0"])
                       (map #(str "toString(" (get-in config [:dimensions %]) ")")
                            group-by))
        order-alias "series0"
        sql (str "SELECT " (str/join ", " selections) "\nFROM " (:source config)
                 "\nWHERE " (str/join " AND " where)
                 (when (seq groups) (str "\nGROUP BY " (str/join ", " groups)))
                 "\nORDER BY "
                 (if bucket-pair
                   (str "bucket0 ASC"
                        (when (seq group-by)
                          (str ", " (str/join ", " (rest groups)) " ASC")))
                   (str order-alias " DESC"
                        (when (seq groups)
                          (str ", " (str/join ", " groups) " ASC"))))
                 "\nLIMIT ?"
                 "\nSETTINGS max_rows_to_read = " max-source-rows
                 ", max_bytes_to_read = " max-source-bytes
                 ", max_execution_time = " max-query-seconds
                 ", timeout_overflow_mode = 'throw'"
                 ", max_memory_usage = " max-query-memory-bytes
                 ", max_threads = 1")
        params (vec (concat (when bucket-nanos [bucket-nanos bucket-nanos])
                            (repeat (count group-by) max-group-text-length)
                            (mapcat #(map :value (:filters %)) series)
                            [start now-unix-nano]
                            (map :value filters) [limit]))]
    {:expression expression :sqlvec (into [sql] params)
     :columns (vec (concat (when bucket-pair [[:bucket0 :bucket-start-unix-nano]])
                           (map-indexed (fn [i field] [(keyword (str "dimension" i)) field]) group-by)
                           (map-indexed (fn [i item] [(keyword (str "series" i)) (:as item)]) series)))
     :calculations calculations
     :limit limit}))
