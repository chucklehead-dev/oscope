(ns oscope.plotje.spec
  "Bounded, portable Plotje/grammar-of-graphics subset used by oscope."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def max-spec-chars 32768)
(def ^:private max-rows 512)
(def ^:private max-columns 16)
(def ^:private max-layers 4)
(def ^:private max-text 160)
(def ^:private top-keys #{:title :x-label :y-label :width :height :data :layers})
(def ^:private layer-keys #{:mark :x :y :color})
(def ^:private marks #{:line :point :bar})

(defn- fail! [message]
  (throw (ex-info message {:oscope.plotje/error true})))
(defn- finite? [x]
  (and (number? x)
       (let [d (double x)] (and (= d d) (not= d ##Inf) (not= d ##-Inf)))))
(defn- unknown! [where allowed value]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! (str where " contains unsupported keys: "
                (str/join ", " (map pr-str (sort-by str unknown)))))))
(defn- label! [key value]
  (when-not (and (string? value) (<= 1 (count value) max-text))
    (fail! (str (name key) " must be a non-empty string of at most "
                max-text " characters")))
  value)
(defn- dim! [key value low high]
  (when-not (and (integer? value) (<= low value high))
    (fail! (str (name key) " must be an integer from " low " to " high)))
  value)
(defn- row! [row]
  (when-not (map? row) (fail! "every data row must be a map"))
  (when-not (<= 1 (count row) max-columns)
    (fail! "data row has an invalid column count"))
  (doseq [[key value] row]
    (when-not (and (keyword? key) (nil? (namespace key))
                   (<= (count (name key)) 64))
      (fail! "data column names must be short, unqualified keywords"))
    (when-not (or (nil? value) (boolean? value) (finite? value)
                  (and (string? value) (<= (count value) max-text))
                  (and (keyword? value) (nil? (namespace value))
                       (<= (count (name value)) 64)))
      (fail! (str "invalid bounded scalar in " (pr-str key)))))
  row)
(defn- column! [rows layer key]
  (let [column (get layer key)]
    (when-not (and (keyword? column) (nil? (namespace column)))
      (fail! (str "layer " (name key) " must name a column")))
    (when-not (every? #(contains? % column) rows)
      (fail! (str "column " (pr-str column) " is missing from one or more rows")))
    column))
(defn- layer! [rows layer]
  (when-not (map? layer) (fail! "every layer must be a map"))
  (unknown! "layer" layer-keys layer)
  (let [mark (:mark layer)
        x (column! rows layer :x)
        y (column! rows layer :y)
        color (when (contains? layer :color) (column! rows layer :color))]
    (when-not (marks mark)
      (fail! "layer mark must be one of :line, :point, or :bar"))
    (when-not (every? #(finite? (get % y)) rows)
      (fail! "layer y column must contain finite numbers"))
    (when (and (#{:line :point} mark)
               (not (every? #(finite? (get % x)) rows)))
      (fail! "line and point x columns must contain finite numbers"))
    (cond-> {:mark mark :x x :y y} color (assoc :color color))))

(defn validate-spec [value]
  (when-not (map? value) (fail! "the chart spec must be an EDN map"))
  (unknown! "chart spec" top-keys value)
  (let [rows (:data value) layers (:layers value)]
    (when-not (and (vector? rows) (<= 1 (count rows) max-rows))
      (fail! "data must be a vector containing from 1 to 512 rows"))
    (doseq [row rows] (row! row))
    (when-not (and (vector? layers) (<= 1 (count layers) max-layers))
      (fail! "layers must contain from 1 to 4 entries"))
    (cond-> {:data rows
             :layers (mapv #(layer! rows %) layers)
             :width (dim! :width (get value :width 760) 320 1200)
             :height (dim! :height (get value :height 420) 240 800)}
      (contains? value :title) (assoc :title (label! :title (:title value)))
      (contains? value :x-label) (assoc :x-label (label! :x-label (:x-label value)))
      (contains? value :y-label) (assoc :y-label (label! :y-label (:y-label value))))))

(defn parse-spec [text]
  (let [text (str (or text ""))]
    (when (> (count text) max-spec-chars) (fail! "chart spec is too large"))
    (when (str/blank? text) (fail! "chart spec is empty"))
    (try
      (validate-spec
       (edn/read-string
        {:readers {}
         :default (fn [tag _]
                    (fail! (str "tagged literal " tag " is not allowed")))}
        text))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable _ (fail! "chart spec is not valid EDN")))))
