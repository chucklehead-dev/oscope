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
(defn- validate-rows [selection rows]
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

(defn screen [plan rows]
  (let [plan (query/validate-plan plan)
        {:keys [signal field window limit] :as selection} (:selection plan)
        data (validate-rows selection rows)
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

(defn query->screen [connection plan]
  (screen plan (query/run connection plan)))
