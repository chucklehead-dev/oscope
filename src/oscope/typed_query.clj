(ns oscope.typed-query
  "Pure validation and UI vocabulary for typed query bindings.")

(def filter-capability
  {:operators {:boolean [:eq] :int64 [:eq :gte :lt]
               :string [:eq :prefix :contains]}
   :signals [:spans]
   :types [:boolean :int64 :string]})

(def log-filter-capability
  {:operators {:boolean [:eq] :int64 [:eq :gte :lt]
               :string [:eq :prefix :contains]}
   :signals [:logs]
   :types [:boolean :int64 :string]})

(def int64-aggregate-capability
  {:aggregates [:count :min :max :avg]
   :group-by [:service-name]
   :predicate-keys [:gte :lt]
   :signals [:spans]
   :types [:int64]})

(def int64-min -9223372036854775808)
(def int64-max 9223372036854775807)

(defn int64? [value]
  (and (integer? value) (<= int64-min value int64-max)))

(def binding-keys
  #{:field-id :attribute-key :attribute-type :attribute-location
    :manifest-version})

(def legacy-binding-keys
  #{:field-id :attribute-key :attribute-type :manifest-version})

(def attribute-locations
  [:resource-attributes :scope-attributes :span-attributes :log-attributes])

(defn location-label [location]
  (case location
    :resource-attributes "Resource"
    :scope-attributes "Scope"
    :span-attributes "Span"
    :log-attributes "Log"
    "Unknown"))

(defn type-label [attribute-type]
  (case attribute-type
    :boolean "Boolean"
    :int64 "Int64"
    :string "String"
    "Unknown"))

(defn operators-for [attribute-type]
  (get-in filter-capability [:operators attribute-type]))

(def max-visible-fields 100)
(def ^:private field-id-pattern #"attribute_[0-9a-f]{20}")

(defn- fail! [type message]
  (throw (ex-info message {:oscope.typed-query/error true :type type})))

(defn- binding-shape? [value expected-keys]
  (and (map? value)
       (= expected-keys (set (keys value)))
       (string? (:field-id value))
       (re-matches field-id-pattern (:field-id value))
       (string? (:attribute-key value))
       (<= 1 (count (:attribute-key value)) 256)
       (contains? (set (:types filter-capability)) (:attribute-type value))
       (integer? (:manifest-version value))
       (<= 1 (:manifest-version value) int64-max)))

(defn binding? [value]
  (and (binding-shape? value binding-keys)
       (contains? (set attribute-locations) (:attribute-location value))))

(defn log-binding? [value]
  (and (binding? value) (= :log-attributes (:attribute-location value))))

(defn legacy-binding? [value]
  (binding-shape? value legacy-binding-keys))

(defn resolve-binding
  "Require a serialized logical binding to equal the startup-confirmed catalog."
  [catalog binding]
  (when-not (or (binding? binding) (legacy-binding? binding))
    (fail! ::invalid-binding "typed schema binding is invalid"))
  (if (binding? binding)
    (or (some #(when (= binding %) %) catalog)
        (fail! ::stale-binding
               "typed schema binding is no longer available"))
    (let [matches (filterv #(= binding (select-keys % legacy-binding-keys))
                           catalog)]
      (if (and (= 1 (count matches))
               (= :span-attributes (:attribute-location (first matches))))
        (first matches)
        (fail! ::stale-binding
               "legacy typed schema binding is no longer available as one span field")))))

(defn resolve-field-id [catalog field-id]
  (when-not (and (string? field-id) (re-matches field-id-pattern field-id))
    (fail! ::invalid-binding "typed schema binding is invalid"))
  (or (some #(when (= field-id (:field-id %)) %) catalog)
      (fail! ::stale-binding
             "typed schema binding is no longer available")))

(defn visible-catalog
  ([catalog] (visible-catalog catalog nil))
  ([catalog selected]
   (let [initial (vec (take max-visible-fields catalog))
         fields (if (or (nil? selected) (some #{selected} initial))
                  initial
                  (conj (vec (take (dec max-visible-fields) catalog)) selected))]
     {:fields fields :total (count catalog)
      :truncated? (> (count catalog) max-visible-fields)})))
