(ns oscope.view-model
  "Pure query result to serializable, renderer-independent UI model."
  (:require [clojure.string :as str]
            [oscope.plotje.spec :as plotje]
            [oscope.query :as query]
            [oscope.typed-query :as typed-query]))

(defn- fail! [type message data]
  (throw (ex-info message
                  (assoc data :oscope.view-model/error true :type type))))
(defn- humanize [x] (-> x name (str/replace "-" " ")))
(defn- title-case [x]
  (str/join " " (map str/capitalize (str/split (humanize x) #" "))))
(defn normalize-rows
  "Validate query result rows and project their renderer-independent shape."
  [selection rows]
  (when-not (vector? rows)
    (fail! ::invalid-rows "oscope distribution rows must be a vector" {:rows rows}))
  (when (> (count rows) (:limit selection))
    (fail! ::too-many-rows "oscope distribution exceeded its query limit"
           {:actual (count rows) :maximum (:limit selection)}))
  (mapv
   (fn [row]
     (when-not (and (map? row)
                    (= (:signal selection) (:signal row))
                    (= (:field selection) (:field row))
                    (string? (:value row))
                    (<= (count (:value row)) query/max-value-length)
                    (integer? (:count row)) (<= 0 (:count row)))
       (fail! ::invalid-row "oscope distribution row does not match its query"
              {:row row :selection selection}))
     {:value (:value row) :count (:count row)})
   rows))

(defn- distribution-screen [plan rows]
  (let [plan (query/validate-plan plan)
        {:keys [signal field window limit] :as selection} (:selection plan)
        data (normalize-rows selection rows)
        chart (when (seq data)
                (plotje/validate-spec
                 {:title (str (title-case field) " in " (title-case signal))
                  :x-label (title-case field) :y-label "Count"
                  :width 760 :height 420 :data data
                  :layers [{:mark :bar :x :value :y :count}]}))]
    {:oscope.view/version 1
     :view :telemetry-distribution
     :status (if (seq data) :ready :empty)
     :title (str (title-case field) " in " (title-case signal))
     :selection selection
     :query-plan plan
     :controls
     {:signals
      (mapv (fn [[candidate fields]]
              {:value candidate :label (title-case candidate)
               :selected? (= signal candidate)
               :fields (mapv (fn [candidate-field]
                               {:value candidate-field
                                :label (title-case candidate-field)
                                :selected? (and (= signal candidate)
                                                (= field candidate-field))})
                             fields)})
            (sort-by (comp str key) (query/supported-fields)))
      :windows (mapv (fn [candidate]
                       {:value candidate :label (name candidate)
                        :selected? (= window candidate)})
                     [:15m :1h :6h :24h])
      :limit {:value limit :minimum 1 :maximum query/max-result-limit}}
     :chart chart
     :table {:columns [{:key :value :label (title-case field)}
                       {:key :count :label "Count"}]
             :rows data}
     :empty-message (when (empty? data)
                      "No telemetry matched this bounded query window.")}))

(defn- finite-number? [value]
  (and (number? value)
       (let [n (double value)]
         (and (= n n) (not= n ##Inf) (not= n ##-Inf)))))

(def ^:private max-plotje-text 160)

(defn- bounded-display [value maximum]
  (if (<= (count value) maximum)
    value
    (str (subs value 0 (dec maximum)) "…")))

(defn- metric-title [metric-name aggregates]
  (let [suffix (str " · " (str/join ", " (map name aggregates)))
        metric-maximum (max 1 (- max-plotje-text (count suffix)))]
    (str (bounded-display metric-name metric-maximum) suffix)))

(defn- normalize-scalar-series-rows [selection request rows]
  (when-not (vector? rows)
    (fail! ::invalid-rows "oscope metric series rows must be a vector"
           (if (= :counter-series (:mode selection))
             {:reason :non-vector-counter-result}
             {:rows rows})))
  (when (> (count rows) (:limit selection))
    (fail! ::too-many-rows "oscope metric series exceeded its query limit"
           {:actual (count rows) :maximum (:limit selection)}))
  (let [{:keys [group-by bucket aggregates metric-name]} selection
        counter? (= :counter-series (:mode selection))
        end-unix-nano (:end-unix-nano request)
        bucket? (not= :none bucket)
        expected-keys (into (set aggregates)
                            (concat group-by
                                    (when bucket?
                                      [:bucket-start-unix-nano])
                                    (when counter?
                                      [:metric-kind :temporality :monotonic?
                                       :interval-count :reset-count
                                       :observed-duration-nanos])))]
    (mapv
     (fn [row]
       (when-not (and (map? row) (= expected-keys (set (keys row))))
         (fail! ::invalid-row "oscope metric series row has unexpected fields"
                (if counter?
                  {:actual-fields (when (map? row) (set (keys row)))
                   :expected-fields expected-keys}
                  {:row row :expected-fields expected-keys})))
       (doseq [field group-by]
         (let [value (get row field)]
           (when-not (and (string? value)
                          (<= (count value) max-plotje-text))
             (fail! ::invalid-row "oscope metric series group is invalid"
                    (if counter? {:field field} {:row row :field field})))))
       (when (and bucket?
                  (not (and (integer? (:bucket-start-unix-nano row))
                            (<= 0 (:bucket-start-unix-nano row)
                                9223372036854775807)
                            (< (:bucket-start-unix-nano row) end-unix-nano))))
         (fail! ::invalid-row "oscope metric series bucket is invalid"
                (if counter? {:field :bucket-start-unix-nano} {:row row})))
       (doseq [aggregate aggregates]
         (let [value (get row aggregate)]
           (when-not (and (finite-number? value)
                          (or (not= :count aggregate)
                              (and (integer? value) (<= 0 value))))
             (fail! ::invalid-row "oscope metric series aggregate is invalid"
                    (if counter? {:aggregate aggregate}
                        {:row row :aggregate aggregate})))))
       (when (and counter?
                  (not (and (= :sum (:metric-kind row))
                            (= :cumulative (:temporality row))
                            (true? (:monotonic? row))
                            (integer? (:interval-count row))
                            (pos? (:interval-count row))
                            (integer? (:reset-count row))
                            (<= 0 (:reset-count row) (:interval-count row))
                            (integer? (:observed-duration-nanos row))
                            (pos? (:observed-duration-nanos row)))))
         (fail! ::invalid-row
                "oscope counter series row has invalid provenance or interval evidence"
                {:reason :invalid-counter-evidence}))
       (cond-> row
         (and (not bucket?) (empty? group-by))
         (assoc :metric-name (bounded-display metric-name max-plotje-text))))
     rows)))

(def ^:private max-histogram-bounds 64)
(def ^:private quantile-fractions
  {:p50 {:value 0.5 :numerator 1 :denominator 2}
   :p95 {:value 0.95 :numerator 19 :denominator 20}
   :p99 {:value 0.99 :numerator 99 :denominator 100}})
(def ^:private quantile-row-keys
  #{:quantile :rank :rank-numerator :rank-denominator :estimate
    :lower-bound :upper-bound :lower-inclusive? :upper-inclusive?
    :lower-unbounded? :upper-unbounded? :bucket-observation-count
    :absolute-error-bound :interpolation})

(defn- valid-bounds? [bounds]
  (and (vector? bounds) (<= (count bounds) max-histogram-bounds)
       (every? finite-number? bounds)
       (every? (fn [[left right]] (< (double left) (double right)))
               (partition 2 1 bounds))))

(defn- containing-bucket [lower upper]
  (str "(" (if (nil? lower) "-Inf" lower) ", "
       (if (nil? upper) "+Inf" upper)
       (if (nil? upper) ")" "]")))

(defn- schema-bucket? [explicit-bounds lower upper]
  (cond
    (and (nil? lower) (nil? upper)) (empty? explicit-bounds)
    (nil? lower) (= upper (first explicit-bounds))
    (nil? upper) (= lower (last explicit-bounds))
    :else (boolean (some #(= [lower upper] %)
                         (partition 2 1 explicit-bounds)))))

(defn- normalize-quantile! [aggregate observation-count explicit-bounds value]
  (when (nil? value)
    (when-not (zero? observation-count)
      (fail! ::invalid-histogram-quantile
             "nonempty cumulative histogram omitted a quantile descriptor"
             {:aggregate aggregate :reason :missing-nonempty-quantile}))
    nil)
  (when value
    (let [{expected-value :value expected-numerator :numerator
           expected-denominator :denominator}
          (get quantile-fractions aggregate)
          {:keys [quantile rank rank-numerator rank-denominator estimate
                  lower-bound upper-bound lower-inclusive? upper-inclusive?
                  lower-unbounded? upper-unbounded? bucket-observation-count
                  absolute-error-bound interpolation]} value
          finite-bucket? (and (some? lower-bound) (some? upper-bound))]
      (when-not (and (map? value) (= quantile-row-keys (set (keys value)))
                     (= expected-value quantile) (finite-number? rank)
                     (integer? rank-numerator) (pos? rank-numerator)
                     (= expected-denominator rank-denominator)
                     (= (* expected-numerator observation-count) rank-numerator)
                     ;; The exact numerator/denominator are authoritative.
                     ;; :rank is the explorer's documented display double and
                     ;; intentionally follows its multiplication order.
                     (= (double rank)
                        (* expected-value (double observation-count)))
                     (or (nil? lower-bound) (finite-number? lower-bound))
                     (or (nil? upper-bound) (finite-number? upper-bound))
                     (schema-bucket? explicit-bounds lower-bound upper-bound)
                     (= (nil? lower-bound) lower-unbounded?)
                     (= (nil? upper-bound) upper-unbounded?)
                     (false? lower-inclusive?)
                     (= (some? upper-bound) upper-inclusive?)
                     (integer? bucket-observation-count)
                     (pos? bucket-observation-count)
                     (<= bucket-observation-count observation-count)
                     (= :uniform-within-explicit-bucket interpolation)
                     (if finite-bucket?
                       (and (finite-number? estimate)
                            (<= (double lower-bound) (double estimate)
                                (double upper-bound))
                            (finite-number? absolute-error-bound)
                            (= (double absolute-error-bound)
                               (double (max (- estimate lower-bound)
                                            (- upper-bound estimate)))))
                       (and (nil? estimate) (nil? absolute-error-bound))))
        (fail! ::invalid-histogram-quantile
               "cumulative histogram quantile descriptor is invalid"
               {:aggregate aggregate :reason :invalid-quantile-descriptor}))
      (let [fields (query/quantile-descriptor-fields aggregate)
            values [estimate lower-bound upper-bound rank-numerator
                    rank-denominator absolute-error-bound interpolation
                    (containing-bucket lower-bound upper-bound)]]
        (zipmap fields values)))))

(defn- normalize-histogram-rows [selection request rows]
  (when-not (vector? rows)
    (fail! ::invalid-rows "oscope cumulative histogram rows must be a vector"
           {:reason :non-vector-histogram-result}))
  (when (> (count rows) (:limit selection))
    (fail! ::too-many-rows "oscope cumulative histogram exceeded its query limit"
           {:actual (count rows) :maximum (:limit selection)}))
  (let [{:keys [group-by bucket aggregates metric-name]} selection
        bucket? (not= :none bucket)
        expected-keys (into (set aggregates)
                            (concat group-by
                                    (when bucket? [:bucket-start-unix-nano])
                                    [:metric-kind :temporality :explicit-bounds
                                     :interval-count :reset-count
                                     :observed-duration-nanos]))
        end-unix-nano (:end-unix-nano request)]
    (mapv
     (fn [row]
       (when-not (and (map? row) (= expected-keys (set (keys row))))
         (fail! ::invalid-row "oscope cumulative histogram row has unexpected fields"
                {:actual-fields (when (map? row) (set (keys row)))
                 :expected-fields expected-keys}))
       (doseq [field group-by]
         (when-not (and (string? (get row field))
                        (<= (count (get row field)) max-plotje-text))
           (fail! ::invalid-row "oscope cumulative histogram group is invalid"
                  {:field field})))
       (when (and bucket?
                  (not (and (integer? (:bucket-start-unix-nano row))
                            (<= 0 (:bucket-start-unix-nano row))
                            (< (:bucket-start-unix-nano row) end-unix-nano))))
         (fail! ::invalid-row "oscope cumulative histogram bucket is invalid"
                {:field :bucket-start-unix-nano}))
       (when-not (and (= :histogram (:metric-kind row))
                      (= :cumulative (:temporality row))
                      (valid-bounds? (:explicit-bounds row))
                      (integer? (:interval-count row))
                      (pos? (:interval-count row))
                      (integer? (:reset-count row))
                      (<= 0 (:reset-count row) (:interval-count row))
                      (integer? (:observed-duration-nanos row))
                      (pos? (:observed-duration-nanos row)))
         (fail! ::invalid-row
                "oscope cumulative histogram provenance or interval evidence is invalid"
                {:reason :invalid-histogram-evidence}))
       (let [observation-count (:count row)]
         (when (and (some #{:count} aggregates)
                    (not (and (integer? observation-count)
                              (<= 0 observation-count))))
           (fail! ::invalid-row "oscope cumulative histogram count is invalid"
                  {:aggregate :count}))
         (let [aggregate-values
               (reduce
                (fn [result aggregate]
                  (if (query/quantile-aggregate? aggregate)
                    (merge result
                           (normalize-quantile! aggregate observation-count
                                                (:explicit-bounds row)
                                                (get row aggregate)))
                    (let [value (get row aggregate)]
                      (when-not
                       (case aggregate
                         :count (and (integer? value) (<= 0 value))
                         :sum (finite-number? value)
                         :avg (if (zero? observation-count)
                                (nil? value) (finite-number? value))
                         false)
                        (fail! ::invalid-row
                               "oscope cumulative histogram aggregate is invalid"
                               {:aggregate aggregate}))
                      (assoc result aggregate value))))
                {} aggregates)]
           (cond-> (merge (select-keys row
                                       (concat group-by
                                               (when bucket?
                                                 [:bucket-start-unix-nano])))
                          aggregate-values
                          (select-keys row [:metric-kind :temporality
                                            :explicit-bounds :interval-count
                                            :reset-count
                                            :observed-duration-nanos]))
             (and (not bucket?) (empty? group-by))
             (assoc :metric-name
                    (bounded-display metric-name max-plotje-text))))))
     rows)))

(defn- normalize-series-rows [selection request rows]
  (if (= :cumulative-histogram-series (:mode selection))
    (normalize-histogram-rows selection request rows)
    (normalize-scalar-series-rows selection request rows)))

(defn- metric-series-screen [plan rows]
  (let [plan (query/validate-plan plan)
        {:keys [mode metric-kind metric-name group-by bucket aggregates window limit]
         :as selection} (:selection plan)
        counter? (= :counter-series mode)
        histogram? (= :cumulative-histogram-series mode)
        options (cond counter? query/counter-series-options
                      histogram? query/cumulative-histogram-series-options
                      :else query/metric-series-options)
        data (normalize-series-rows selection (:request plan) rows)
        bucket? (not= :none bucket)
        x (cond bucket? :bucket-start-unix-nano
                (seq group-by) (first group-by)
                :else :metric-name)
        color (cond bucket? (first group-by)
                    (> (count group-by) 1) (second group-by)
                    :else nil)
        mark (if bucket? :line :bar)
        primary-aggregate (if histogram?
                            (or (first (remove #{:count} aggregates)) :count)
                            (first aggregates))
        primary-field (if (query/quantile-aggregate? primary-aggregate)
                        (first (query/quantile-descriptor-fields
                                primary-aggregate))
                        primary-aggregate)
        layers [(cond-> {:mark mark :x x :y primary-field}
                  color (assoc :color color))]
        title (metric-title metric-name aggregates)
        chart (when (and (seq data)
                         (some #(finite-number? (get % primary-field)) data))
                (plotje/validate-spec
                 {:title (metric-title metric-name [primary-aggregate])
                  :x-label (title-case x)
                  :y-label (title-case primary-field)
                  :width 760 :height 420 :data data :layers layers}))
        table-fields
        (if histogram?
          (query/cumulative-histogram-series-output-fields selection)
          (vec (concat (when (and (not bucket?) (empty? group-by))
                         [:metric-name])
                       (when bucket? [:bucket-start-unix-nano])
                       group-by aggregates
                       (when counter?
                         [:interval-count :reset-count
                          :observed-duration-nanos :metric-kind
                          :temporality :monotonic?]))))]
    {:oscope.view/version 1
     :view (if histogram? :telemetry-cumulative-histogram-series
               :telemetry-metric-series)
     :status (if (seq data) :ready :empty)
     :title title
     :selection selection
     :query-plan plan
     :controls
     {:mode mode
      :metric-kinds (:metric-kinds options)
      :group-by (:group-by options)
      :buckets (:buckets options)
      :aggregates (if (or counter? histogram?) (:aggregates options)
                      (get-in options [:aggregates metric-kind]))
      :windows (keys query/windows)
      :limit {:value limit :minimum 1 :maximum query/max-result-limit}}
     :chart chart
     :table {:columns (mapv (fn [field]
                              {:key field :label (title-case field)})
                            table-fields)
             :rows data}
     :empty-message
     (cond
       (empty? data)
       (cond
         counter? "No exact counter intervals matched this bounded recipe."
         histogram?
         "No reconstructable cumulative histogram intervals matched this bounded recipe."
         :else "No metric points matched this bounded series recipe.")

       (and histogram? (nil? chart))
       "The selected histogram estimate is null (empty or in an infinite-tail bucket); exact rank and containing bounds remain in the table."

       :else nil)}))

(def ^:private coverage-order
  [:valid :present-empty :absent :invalid :historical-untyped-fallback
   :historical-untyped-unavailable])
(def ^:private coverage-keys (conj (set coverage-order) :total))
(def ^:private typed-row-keys
  #{:attribute-key :attribute-type :attribute-value :field-id
    :manifest-version :parent-span-id :service-name :signal :source :span-id
    :span-name :timestamp-unix-nano :trace-id :typed-status})

(defn- valid-bounded-text? [value]
  (and (string? value) (<= (count value) max-plotje-text)))

(defn- typed-span-filter-screen [plan result typed-span-fields]
  (let [plan (query/validate-plan plan)
        {:keys [schema-binding operator value limit] :as selection} (:selection plan)
        binding (typed-query/resolve-binding typed-span-fields schema-binding)
        expected {:attribute-key (:attribute-key binding)
                  :attribute-type (:attribute-type binding)
                  :field-id (:field-id binding)
                  :manifest-version (:manifest-version binding)
                  :signal :spans}]
    (let [coverage (:coverage result)
          counts (when (map? coverage) (mapv coverage coverage-order))]
      (when-not (and (map? result)
                   (= expected (select-keys result (keys expected)))
                   (= {:operator operator :value value} (:filter result))
                   (map? coverage) (= coverage-keys (set (keys coverage)))
                   (every? #(and (integer? %) (not (neg? %))) counts)
                   (integer? (:total coverage)) (not (neg? (:total coverage)))
                   (= (:total coverage) (reduce + 0 counts))
                   (vector? (:matches result))
                   (<= (count (:matches result)) limit))
        (fail! ::invalid-typed-result "typed span filter result does not match its schema binding" {})))
    (let [rows (mapv (fn [row]
                       (when-not (and (map? row)
                                      (= typed-row-keys (set (keys row)))
                                      (= (:field-id binding) (:field-id row))
                                      (= (:manifest-version binding) (:manifest-version row))
                                      (= (:attribute-key binding) (:attribute-key row))
                                      (= (:attribute-type binding) (:attribute-type row))
                                      (= :typed (:source row))
                                      (= :spans (:signal row))
                                      (integer? (:timestamp-unix-nano row))
                                      (<= 0 (:timestamp-unix-nano row)
                                          9223372036854775807)
                                      (every? valid-bounded-text?
                                              ((juxt :trace-id :span-id
                                                     :parent-span-id :service-name
                                                     :span-name) row))
                                      (case (:attribute-type binding)
                                        :boolean (and (= 3 (:typed-status row))
                                                      (boolean? (:attribute-value row)))
                                        :int64 (and (= 3 (:typed-status row))
                                                    (typed-query/int64?
                                                     (:attribute-value row)))
                                        :string (and (contains? #{2 3} (:typed-status row))
                                                     (string? (:attribute-value row))
                                                     (<= (count (:attribute-value row))
                                                         query/max-value-length)
                                                     (= (= 2 (:typed-status row))
                                                        (empty? (:attribute-value row))))
                                        false))
                         (fail! ::invalid-typed-row "typed span row does not match its schema binding" {}))
                       (assoc row :display-value
                              (if (and (= :string (:attribute-type binding))
                                       (= "" (:attribute-value row)))
                                "(empty string)" (str (:attribute-value row)))))
                     (:matches result))
          visible (typed-query/visible-catalog typed-span-fields binding)]
      {:oscope.view/version 1 :view :telemetry-typed-span-filter
       :status (if (seq rows) :ready :empty)
       :title (str "Typed spans · " (:attribute-key binding))
       :selection selection :query-plan plan :chart nil
       :controls {:typed-span-fields (:fields visible)
                  :typed-span-field-total (:total visible)
                  :typed-span-fields-truncated? (:truncated? visible)
                  :windows (keys query/windows)
                  :limit {:value limit :minimum 1 :maximum query/max-result-limit}}
       :coverage (conj
                  (mapv (fn [status] {:status status
                                      :count (get (:coverage result) status)})
                        coverage-order)
                  {:status :total :count (get-in result [:coverage :total])})
       :freshness-notice
       "Coverage and matching spans are evaluated by two bounded live queries; concurrent ingestion can advance one between them."
       :table {:columns [{:key :timestamp-unix-nano :label "Timestamp (Unix ns)"}
                         {:key :service-name :label "Service"}
                         {:key :span-name :label "Span"}
                         {:key :display-value :label "Typed value"}
                         {:key :trace-id :label "Trace ID"}
                         {:key :span-id :label "Span ID"}]
               :rows rows}
       :empty-message (when (empty? rows) "No typed spans matched this bounded query window.")})))

(def ^:private typed-int64-aggregate-base-row-keys
  #{:attribute-key :field-id :manifest-version :signal :source :typed-status})

(defn- typed-span-int64-aggregate-screen [plan result typed-span-fields]
  (let [plan (query/validate-plan plan)
        {:keys [schema-binding group-by aggregates limit] :as selection}
        (:selection plan)
        binding (typed-query/resolve-binding typed-span-fields schema-binding)
        expected {:attribute-key (:attribute-key binding)
                  :attribute-type :int64
                  :field-id (:field-id binding)
                  :manifest-version (:manifest-version binding)
                  :signal :spans}
        coverage (:coverage result)
        counts (when (map? coverage) (mapv coverage coverage-order))
        rows (:aggregates result)]
    (when-not (and (= :int64 (:attribute-type binding))
                   (map? result)
                   (= #{:attribute-key :attribute-type :field-id
                        :manifest-version :signal :coverage :aggregates}
                      (set (keys result)))
                   (= expected (select-keys result (keys expected)))
                   (map? coverage) (= coverage-keys (set (keys coverage)))
                   (every? #(and (integer? %) (not (neg? %))) counts)
                   (integer? (:total coverage)) (not (neg? (:total coverage)))
                   (= (:total coverage) (reduce + 0 counts))
                   (vector? rows) (<= (count rows) limit))
      (fail! ::invalid-typed-aggregate-result
             "typed Int64 aggregate result does not match its schema binding" {}))
    (let [expected-row-keys
          (into typed-int64-aggregate-base-row-keys (concat group-by aggregates))
          rows
          (mapv
           (fn [row]
             (when-not
              (and (map? row) (= expected-row-keys (set (keys row)))
                   (= (:attribute-key binding) (:attribute-key row))
                   (= (:field-id binding) (:field-id row))
                   (= (:manifest-version binding) (:manifest-version row))
                   (= :spans (:signal row)) (= :typed (:source row))
                   (= 3 (:typed-status row))
                   (every? #(valid-bounded-text? (get row %)) group-by)
                   (every?
                    (fn [aggregate]
                      (let [value (get row aggregate)]
                        (case aggregate
                          :count (and (integer? value) (not (neg? value)))
                          (:min :max) (typed-query/int64? value)
                          :avg (finite-number? value)
                          false)))
                    aggregates))
               (fail! ::invalid-typed-aggregate-row
                      "typed Int64 aggregate row does not match its query" {}))
             row)
           rows)
          visible (typed-query/visible-catalog typed-span-fields binding)]
      {:oscope.view/version 1
       :view :telemetry-typed-span-int64-aggregate
       :status (if (seq rows) :ready :empty)
       :title (str "Typed Int64 summary · " (:attribute-key binding))
       :selection selection :query-plan plan :chart nil
       :controls {:typed-span-fields (:fields visible)
                  :typed-span-field-total (:total visible)
                  :typed-span-fields-truncated? (:truncated? visible)
                  :windows (keys query/windows)
                  :limit {:value limit :minimum 1 :maximum query/max-result-limit}}
       :coverage (conj
                  (mapv (fn [status]
                          {:status status :count (get coverage status)})
                        coverage-order)
                  {:status :total :count (:total coverage)})
       :freshness-notice
       "Coverage and aggregates are evaluated by two bounded live queries; concurrent ingestion can advance one between them. Only valid typed rows contribute to count, min, max, and avg."
       :table {:columns
               (vec (concat
                     (map (fn [group] {:key group :label (title-case group)})
                          group-by)
                     (map (fn [aggregate]
                            {:key aggregate :label (str/upper-case (name aggregate))})
                          aggregates)))
               :rows rows}
       :empty-message
       (when (empty? rows)
         "No valid typed Int64 values matched this bounded range and window.")})))

(defn screen
  ([plan rows] (screen plan rows {}))
  ([plan rows {:keys [typed-span-fields]}]
   (let [mode (get-in plan [:selection :mode])
         result (cond
                  (= :typed-span-int64-aggregate mode)
                  (typed-span-int64-aggregate-screen plan rows typed-span-fields)
                  (= :typed-span-filter mode)
                  (typed-span-filter-screen plan rows typed-span-fields)
                  (contains? #{:metric-series :counter-series
                               :cumulative-histogram-series} mode)
                  (metric-series-screen plan rows)
                  :else (distribution-screen plan rows))]
     (if (and (seq typed-span-fields)
              (not (contains? #{:typed-span-filter
                                :typed-span-int64-aggregate} mode)))
       (let [{:keys [fields total truncated?]}
             (typed-query/visible-catalog typed-span-fields)]
         (-> result
             (assoc-in [:controls :typed-span-fields] fields)
             (assoc-in [:controls :typed-span-field-total] total)
             (assoc-in [:controls :typed-span-fields-truncated?] truncated?)))
       result))))
