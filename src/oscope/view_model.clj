(ns oscope.view-model
  "Pure query result to serializable, renderer-independent UI model."
  (:require [clojure.string :as str]
            [oscope.plotje.spec :as plotje]
            [oscope.query :as query]))

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

(defn- normalize-series-rows [selection request rows]
  (when-not (vector? rows)
    (fail! ::invalid-rows "oscope metric series rows must be a vector"
           {:rows rows}))
  (when (> (count rows) (:limit selection))
    (fail! ::too-many-rows "oscope metric series exceeded its query limit"
           {:actual (count rows) :maximum (:limit selection)}))
  (let [{:keys [group-by bucket aggregates metric-name]} selection
        end-unix-nano (:end-unix-nano request)
        bucket? (not= :none bucket)
        expected-keys (into (set aggregates)
                            (concat group-by
                                    (when bucket?
                                      [:bucket-start-unix-nano])))]
    (mapv
     (fn [row]
       (when-not (and (map? row) (= expected-keys (set (keys row))))
         (fail! ::invalid-row "oscope metric series row has unexpected fields"
                {:row row :expected-fields expected-keys}))
       (doseq [field group-by]
         (let [value (get row field)]
           (when-not (and (string? value)
                          (<= (count value) max-plotje-text))
             (fail! ::invalid-row "oscope metric series group is invalid"
                    {:row row :field field}))))
       (when (and bucket?
                  (not (and (integer? (:bucket-start-unix-nano row))
                            (<= 0 (:bucket-start-unix-nano row)
                                9223372036854775807)
                            (< (:bucket-start-unix-nano row) end-unix-nano))))
         (fail! ::invalid-row "oscope metric series bucket is invalid"
                {:row row}))
       (doseq [aggregate aggregates]
         (let [value (get row aggregate)]
           (when-not (and (finite-number? value)
                          (or (not= :count aggregate)
                              (and (integer? value) (<= 0 value))))
             (fail! ::invalid-row "oscope metric series aggregate is invalid"
                    {:row row :aggregate aggregate}))))
       (cond-> row
         (and (not bucket?) (empty? group-by))
         (assoc :metric-name (bounded-display metric-name max-plotje-text))))
     rows)))

(defn- metric-series-screen [plan rows]
  (let [plan (query/validate-plan plan)
        {:keys [metric-kind metric-name group-by bucket aggregates window limit]
         :as selection} (:selection plan)
        data (normalize-series-rows selection (:request plan) rows)
        bucket? (not= :none bucket)
        x (cond bucket? :bucket-start-unix-nano
                (seq group-by) (first group-by)
                :else :metric-name)
        color (cond bucket? (first group-by)
                    (> (count group-by) 1) (second group-by)
                    :else nil)
        mark (if bucket? :line :bar)
        primary-aggregate (first aggregates)
        layers [(cond-> {:mark mark :x x :y primary-aggregate}
                  color (assoc :color color))]
        title (metric-title metric-name aggregates)
        chart (when (seq data)
                (plotje/validate-spec
                 {:title (metric-title metric-name [primary-aggregate])
                  :x-label (title-case x)
                  :y-label (title-case primary-aggregate)
                  :width 760 :height 420 :data data :layers layers}))
        table-fields (vec (concat (when (and (not bucket?) (empty? group-by))
                                    [:metric-name])
                                  (when bucket? [:bucket-start-unix-nano])
                                  group-by aggregates))]
    {:oscope.view/version 1
     :view :telemetry-metric-series
     :status (if (seq data) :ready :empty)
     :title title
     :selection selection
     :query-plan plan
     :controls
     {:mode :metric-series
      :metric-kinds (:metric-kinds query/metric-series-options)
      :group-by (:group-by query/metric-series-options)
      :buckets (:buckets query/metric-series-options)
      :aggregates (get-in query/metric-series-options
                          [:aggregates metric-kind])
      :windows (keys query/windows)
      :limit {:value limit :minimum 1 :maximum query/max-result-limit}}
     :chart chart
     :table {:columns (mapv (fn [field]
                              {:key field :label (title-case field)})
                            table-fields)
             :rows data}
     :empty-message (when (empty? data)
                      "No metric points matched this bounded series recipe.")}))

(defn screen [plan rows]
  (if (= :metric-series (get-in plan [:selection :mode]))
    (metric-series-screen plan rows)
    (distribution-screen plan rows)))
