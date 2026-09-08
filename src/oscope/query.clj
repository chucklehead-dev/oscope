(ns oscope.query
  "Pure, bounded, SQL-free query plans for the telemetry explorer."
  (:require [clojure.string :as str]
            [oscope.error :as error]))

(def max-time-range-nanos (* 24 60 60 1000000000))
(def max-result-limit 100)
(def max-value-length 256)

(def ^:private fields
  {:spans [:service-name :span-name :span-kind :status-code :scope-name
           :http-request-method :http-response-status-code
           :deployment-environment]
   :logs [:service-name :severity-text :event-name :scope-name
          :deployment-environment]
   :metrics [:service-name :metric-name :metric-unit :scope-name
             :deployment-environment]})

(def windows
  {:15m (* 15 60 1000000000)
   :1h  (* 60 60 1000000000)
   :6h  (* 6 60 60 1000000000)
   :24h max-time-range-nanos})

(def default-selection
  {:signal :spans :field :service-name :window :1h :limit 12})

(def metric-series-options
  {:metric-kinds [:gauge :sum :histogram]
   :group-by [:service-name :metric-unit :scope-name
              :deployment-environment]
   :buckets [:none :1m :5m :15m :1h]
   :aggregates
   {:gauge [:count :sum :min :max :avg :p50 :p95 :p99]
    :sum [:count :sum :min :max :avg :p50 :p95 :p99]
    :histogram [:count :sum :avg]}})

(def counter-series-options
  {:metric-kinds [:sum]
   :temporalities [:cumulative]
   :monotonic-values [true]
   :group-by [:service-name :metric-unit :scope-name
              :deployment-environment]
   :buckets [:none :1m :5m :15m :1h]
   :aggregates [:increase :rate]})

(def cumulative-histogram-series-options
  {:metric-kinds [:histogram]
   :temporalities [:cumulative]
   :group-by [:service-name :metric-unit :scope-name
              :deployment-environment]
   :buckets [:none :1m :5m :15m :1h]
   :aggregates [:count :sum :avg :p50 :p95 :p99]
   :quantiles {:p50 0.5 :p95 0.95 :p99 0.99}})

(def default-metric-series-selection
  {:mode :metric-series
   :metric-kind :gauge
   :metric-name "queue.depth"
   :group-by [:service-name]
   :bucket :5m
   :aggregates [:avg :p95]
   :window :1h
   :limit 100})

(def default-counter-series-selection
  {:mode :counter-series
   :metric-kind :sum
   :temporality :cumulative
   :monotonic? true
   :metric-name "requests.total"
   :group-by [:service-name]
   :bucket :none
   :aggregates [:increase :rate]
   :window :1h
   :limit 100})

(def default-cumulative-histogram-series-selection
  {:mode :cumulative-histogram-series
   :metric-kind :histogram
   :temporality :cumulative
   :metric-name "request.duration"
   :group-by [:service-name]
   :bucket :none
   :aggregates [:count :avg :p95]
   :window :1h
   :limit 100})

(def ^:private selection-keys #{:signal :field :window :limit})
(def ^:private metric-series-selection-keys
  #{:mode :metric-kind :metric-name :group-by :bucket :aggregates
    :window :limit})
(def ^:private counter-series-selection-keys
  #{:mode :metric-kind :temporality :monotonic? :metric-name :group-by
    :bucket :aggregates :window :limit})
(def ^:private cumulative-histogram-series-selection-keys
  #{:mode :metric-kind :temporality :metric-name :group-by :bucket
    :aggregates :window :limit})

(declare quantile-aggregate?)

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :oscope.query/error true :type type))))

(defn- window-nanos [window]
  (let [nanos (get windows window)]
    (if (integer? nanos)
      nanos
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window}))))

(defn supported-fields [] fields)

(defn- normalize-distribution-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection "oscope selection must be a map"
           {:selection selection}))
  (when-let [unknown (seq (remove selection-keys (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope selection contains unsupported keys"
           {:keys (error/sorted-keys unknown)}))
  (let [{:keys [signal field window limit]}
        (merge default-selection selection)
        fields (get (supported-fields) signal)]
    (when-not fields
      (fail! ::unsupported-signal "oscope signal is not supported"
             {:signal signal :supported-signals (vec (keys (supported-fields)))}))
    (when-not (some #{field} fields)
      (fail! ::unsupported-field "oscope field is not supported for this signal"
             {:signal signal :field field :supported-fields fields}))
    (when-not (contains? windows window)
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window :supported-windows (vec (keys windows))}))
    (when-not (and (integer? limit) (<= 1 limit max-result-limit))
      (fail! ::invalid-limit "oscope result limit is outside the explorer cap"
             {:limit limit :minimum 1 :maximum max-result-limit}))
    {:signal signal :field field :window window :limit limit}))

(defn- valid-closed-vector? [value allowed maximum]
  (and (vector? value)
       (<= (count value) maximum)
       (= (count value) (count (set value)))
       (every? (set allowed) value)))

(defn normalize-metric-series-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection "oscope metric series selection must be a map"
           {:selection selection}))
  (when-let [unknown (seq (remove metric-series-selection-keys
                                  (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope metric series selection contains unsupported keys"
           {:keys (error/sorted-keys unknown)}))
  (let [{:keys [mode metric-kind metric-name group-by bucket aggregates
                window limit]}
        (merge default-metric-series-selection selection)
        allowed-aggregates (get-in metric-series-options
                                   [:aggregates metric-kind])]
    (when-not (= :metric-series mode)
      (fail! ::unsupported-mode "oscope query mode is not supported"
             {:mode mode :supported-modes [:metric-series]}))
    (when-not allowed-aggregates
      (fail! ::unsupported-metric-kind "oscope metric kind is not supported"
             {:metric-kind metric-kind
              :supported-metric-kinds (:metric-kinds metric-series-options)}))
    (when-not (and (string? metric-name) (not (str/blank? metric-name))
                   (<= (count metric-name) max-value-length))
      (fail! ::invalid-metric-name
             "oscope metric name must contain from 1 to 256 characters"
             {:metric-name metric-name :maximum max-value-length}))
    (when-not (valid-closed-vector? group-by
                                    (:group-by metric-series-options) 1)
      (fail! ::invalid-group-by
             "oscope charts support zero or one metric group dimension"
             {:group-by group-by :supported (:group-by metric-series-options)}))
    (when-not (some #{bucket} (:buckets metric-series-options))
      (fail! ::unsupported-bucket "oscope metric bucket is not supported"
             {:bucket bucket :supported (:buckets metric-series-options)}))
    (when-not (and (valid-closed-vector? aggregates allowed-aggregates 4)
                   (seq aggregates))
      (fail! ::invalid-aggregates
             "oscope metric aggregates must select from one to four supported fields"
             {:aggregates aggregates :supported allowed-aggregates}))
    (when-not (contains? windows window)
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window :supported-windows (vec (keys windows))}))
    (when-not (and (integer? limit) (<= 1 limit max-result-limit))
      (fail! ::invalid-limit "oscope result limit is outside the explorer cap"
             {:limit limit :minimum 1 :maximum max-result-limit}))
    {:mode :metric-series :metric-kind metric-kind :metric-name metric-name
     :group-by (vec (filter (set group-by)
                            (:group-by metric-series-options)))
     :bucket bucket
     :aggregates (vec (filter (set aggregates) allowed-aggregates))
     :window window :limit limit}))

(defn normalize-counter-series-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection "oscope counter series selection must be a map"
           {:selection selection}))
  (when-let [unknown (seq (remove counter-series-selection-keys
                                  (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope counter series selection contains unsupported keys"
           {:keys (error/sorted-keys unknown)}))
  (let [{:keys [mode metric-kind temporality monotonic? metric-name group-by
                bucket aggregates window limit]}
        (merge default-counter-series-selection selection)]
    (when-not (= :counter-series mode)
      (fail! ::unsupported-mode "oscope query mode is not supported"
             {:mode mode :supported-modes [:counter-series]}))
    (when-not (and (= :sum metric-kind) (= :cumulative temporality)
                   (true? monotonic?))
      (fail! ::unsupported-counter-provenance
             "oscope counter series requires cumulative monotonic sum provenance"
             {:metric-kind metric-kind :temporality temporality
              :monotonic? monotonic?}))
    (when-not (and (string? metric-name) (not (str/blank? metric-name))
                   (<= (count metric-name) max-value-length))
      (fail! ::invalid-metric-name
             "oscope metric name must contain from 1 to 256 characters"
             {:maximum max-value-length}))
    (when-not (valid-closed-vector? group-by
                                    (:group-by counter-series-options) 1)
      (fail! ::invalid-group-by
             "oscope charts support zero or one counter group dimension"
             {:group-by group-by :supported (:group-by counter-series-options)}))
    (when-not (some #{bucket} (:buckets counter-series-options))
      (fail! ::unsupported-bucket "oscope counter bucket is not supported"
             {:bucket bucket :supported (:buckets counter-series-options)}))
    (when-not (and (valid-closed-vector? aggregates
                                         (:aggregates counter-series-options) 2)
                   (seq aggregates))
      (fail! ::invalid-aggregates
             "oscope counter aggregates must select increase and/or rate"
             {:aggregates aggregates
              :supported (:aggregates counter-series-options)}))
    (when-not (contains? windows window)
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window :supported-windows (vec (keys windows))}))
    (when-not (and (integer? limit) (<= 1 limit max-result-limit))
      (fail! ::invalid-limit "oscope result limit is outside the explorer cap"
             {:limit limit :minimum 1 :maximum max-result-limit}))
    {:mode :counter-series :metric-kind :sum :temporality :cumulative
     :monotonic? true :metric-name metric-name
     :group-by (vec (filter (set group-by) (:group-by counter-series-options)))
     :bucket bucket
     :aggregates (vec (filter (set aggregates)
                              (:aggregates counter-series-options)))
     :window window :limit limit}))

(defn normalize-cumulative-histogram-series-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection
           "oscope cumulative histogram selection must be a map"
           {:reason :non-map-histogram-selection}))
  (when-let [unknown (seq (remove cumulative-histogram-series-selection-keys
                                  (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope cumulative histogram selection contains unsupported keys"
           {:keys (error/sorted-keys unknown)}))
  (let [{:keys [mode metric-kind temporality metric-name group-by bucket
                aggregates window limit]}
        (merge default-cumulative-histogram-series-selection selection)
        options cumulative-histogram-series-options]
    (when-not (= :cumulative-histogram-series mode)
      (fail! ::unsupported-mode "oscope query mode is not supported"
             {:mode mode :supported-modes [:cumulative-histogram-series]}))
    (when-not (and (= :histogram metric-kind) (= :cumulative temporality))
      (fail! ::unsupported-histogram-provenance
             "oscope histogram series requires cumulative explicit-histogram provenance"
             {:metric-kind metric-kind :temporality temporality}))
    (when-not (and (string? metric-name) (not (str/blank? metric-name))
                   (<= (count metric-name) max-value-length))
      (fail! ::invalid-metric-name
             "oscope metric name must contain from 1 to 256 characters"
             {:reason :invalid-metric-name :maximum max-value-length}))
    (when-not (valid-closed-vector? group-by (:group-by options) 1)
      (fail! ::invalid-group-by
             "oscope charts support zero or one histogram group dimension"
             {:group-by group-by :supported (:group-by options)}))
    (when-not (some #{bucket} (:buckets options))
      (fail! ::unsupported-bucket "oscope histogram bucket is not supported"
             {:bucket bucket :supported (:buckets options)}))
    (when-not (and (valid-closed-vector? aggregates (:aggregates options) 6)
                   (seq aggregates))
      (fail! ::invalid-aggregates
             "oscope cumulative histogram aggregates must select one to six fields"
             {:aggregates aggregates :supported (:aggregates options)}))
    (when (and (some #(or (= :avg %) (quantile-aggregate? %)) aggregates)
               (not (some #{:count} aggregates)))
      (fail! ::missing-histogram-count
             "oscope histogram averages and quantiles require count for explicit empty semantics"
             {:required :count}))
    (when-not (contains? windows window)
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window :supported-windows (vec (keys windows))}))
    (when-not (and (integer? limit) (<= 1 limit max-result-limit))
      (fail! ::invalid-limit "oscope result limit is outside the explorer cap"
             {:limit limit :minimum 1 :maximum max-result-limit}))
    {:mode :cumulative-histogram-series
     :metric-kind :histogram :temporality :cumulative
     :metric-name metric-name
     :group-by (vec (filter (set group-by) (:group-by options)))
     :bucket bucket
     :aggregates (vec (filter (set aggregates) (:aggregates options)))
     :window window :limit limit}))

(def ^:private quantile-descriptor-suffixes
  [:estimate :lower-bound :upper-bound :rank-numerator :rank-denominator
   :absolute-error-bound :interpolation :containing-bucket])

(defn quantile-aggregate? [aggregate]
  (contains? #{:p50 :p95 :p99} aggregate))

(defn quantile-descriptor-fields [aggregate]
  (when-not (quantile-aggregate? aggregate)
    (fail! ::invalid-quantile "oscope histogram quantile is not supported"
           {:aggregate aggregate :supported [:p50 :p95 :p99]}))
  (mapv #(keyword (str (name aggregate) "-" (name %)))
        quantile-descriptor-suffixes))

(defn cumulative-histogram-series-output-fields [selection]
  (let [{:keys [group-by bucket aggregates] :as selection}
        (normalize-cumulative-histogram-series-selection selection)]
    (vec (concat (when (and (= :none bucket) (empty? group-by))
                   [:metric-name])
                 (when (not= :none bucket) [:bucket-start-unix-nano])
                 group-by
                 (mapcat #(if (quantile-aggregate? %)
                            (quantile-descriptor-fields %) [%]) aggregates)
                 [:explicit-bounds :interval-count :reset-count
                  :observed-duration-nanos :metric-kind :temporality]))))

(defn counter-series-output-fields [selection]
  (let [{:keys [group-by bucket aggregates] :as selection}
        (normalize-counter-series-selection selection)]
    (vec (concat (when (and (= :none bucket) (empty? group-by))
                   [:metric-name])
                 (when (not= :none bucket) [:bucket-start-unix-nano])
                 group-by aggregates
                 [:interval-count :reset-count :observed-duration-nanos
                  :metric-kind :temporality :monotonic?]))))

(defn normalize-selection [selection]
  (case (:mode selection)
    :metric-series (normalize-metric-series-selection selection)
    :counter-series (normalize-counter-series-selection selection)
    :cumulative-histogram-series
    (normalize-cumulative-histogram-series-selection selection)
    (normalize-distribution-selection selection)))

(defn compile-query [selection end-unix-nano]
  (when-not (and (integer? end-unix-nano)
                 (<= 1 end-unix-nano 9223372036854775807))
    (fail! ::invalid-time "oscope query end must be epoch nanoseconds"
           {:end-unix-nano end-unix-nano}))
  (let [{:keys [signal field window limit] :as selected}
        (normalize-selection selection)
        start (max 0 (- end-unix-nano (window-nanos window)))]
    (case (:mode selected)
      :cumulative-histogram-series
      {:oscope.query/version 1
       :selection selected
       :request {:metric-kind (:metric-kind selected)
                 :temporality (:temporality selected)
                 :metric-name (:metric-name selected)
                 :group-by (:group-by selected)
                 :bucket (:bucket selected)
                 :aggregates (:aggregates selected)
                 :start-unix-nano start :end-unix-nano end-unix-nano
                 :limit limit}}

      :counter-series
      {:oscope.query/version 1
       :selection selected
       :request {:metric-kind (:metric-kind selected)
                 :temporality (:temporality selected)
                 :monotonic? (:monotonic? selected)
                 :metric-name (:metric-name selected)
                 :group-by (:group-by selected)
                 :bucket (:bucket selected)
                 :aggregates (:aggregates selected)
                 :start-unix-nano start :end-unix-nano end-unix-nano
                 :limit limit}}

      :metric-series
      {:oscope.query/version 1
       :selection selected
       :request {:metric-kind (:metric-kind selected)
                 :metric-name (:metric-name selected)
                 :group-by (:group-by selected)
                 :bucket (:bucket selected)
                 :aggregates (:aggregates selected)
                 :start-unix-nano start :end-unix-nano end-unix-nano
                 :limit limit}}

      {:oscope.query/version 1
       :selection selected
       :request {:signal signal :fields [field]
                 :start-unix-nano start :end-unix-nano end-unix-nano
                 :limit limit}})))

(defn validate-plan [plan]
  (when-not (and (map? plan) (= 1 (:oscope.query/version plan)))
    (fail! ::invalid-plan "oscope query plan version is not supported"
           {:version (when (map? plan) (:oscope.query/version plan))}))
  (let [expected (compile-query (:selection plan)
                                (:end-unix-nano (:request plan)))]
    (when-not (= expected plan)
      (fail! ::invalid-plan "oscope query plan does not match its selection"
             {:plan plan}))
    plan))
