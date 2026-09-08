(ns oscope.plotje.spec
  "Bounded, portable Plotje/grammar-of-graphics subset used by oscope."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [oscope.query.expression :as query-expression]))

(def max-spec-chars 32768)
(def ^:private max-rows 512)
(def ^:private max-columns 16)
(def ^:private max-data-sources 4)
(def ^:private max-layers 4)
(def ^:private max-text 160)
(def ^:private top-keys
  #{:title :x-label :y-label :width :height :grid? :palette :data :layers})
(def ^:private layer-keys
  #{:mark :x :y :color :stroke :fill :opacity :stroke-width :point-radius
    :bar-width})
(def marks #{:line :point :bar :area :rule :tick})
(def ^:private color-pattern #"#[0-9a-fA-F]{6}")
(def ^:private data-reference-keys #{:source :query :select})

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
(defn- bounded-number! [key value low high]
  (when-not (and (finite? value) (<= low (double value) high))
    (fail! (str (name key) " must be a finite number from " low " to " high)))
  value)
(defn- color! [key value]
  (when-not (and (string? value) (re-matches color-pattern value))
    (fail! (str (name key) " must be a six-digit hex color such as #3b82f6")))
  value)
(defn- palette! [value]
  (when-not (and (vector? value) (<= 1 (count value) 8))
    (fail! "palette must contain from 1 to 8 colors"))
  (mapv #(color! :palette %) value))
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
(defn- column-name! [message value]
  (when-not (and (keyword? value) (nil? (namespace value))
                 (<= 1 (count (name value)) 64))
    (fail! message))
  value)
(defn- data-reference! [value]
  (when-not (map? value)
    (fail! "data must be literal rows or a named data source"))
  (unknown! "data reference" data-reference-keys value)
  (let [source (column-name! "data source must be a short, unqualified keyword"
                             (:source value))
        fields (:select value)
        query (:query value)]
    (when-not (and (vector? fields) (<= 1 (count fields) max-columns))
      (fail! "data select must contain from 1 to 16 fields"))
    (doseq [field fields]
      (column-name! "selected fields must be short, unqualified keywords" field))
    (when-not (= (count fields) (count (distinct fields)))
      (fail! "data select fields must be unique"))
    (if (= :telemetry-query source)
      (let [query (query-expression/validate-expression query)
            available (set (query-expression/output-fields query))]
        (doseq [field fields]
          (when-not (contains? available field)
            (fail! (str "selected field " (pr-str field)
                        " is not produced by telemetry query"))))
        {:source source :query query :select fields})
      (do
        (when (contains? value :query)
          (fail! "query is supported only by the :telemetry-query data source"))
        {:source source :select fields}))))
(defn validate-data-sources [sources]
  (when-not (and (map? sources) (<= (count sources) max-data-sources)
                 (every? #(and (keyword? %) (nil? (namespace %))
                               (<= 1 (count (name %)) 64))
                         (keys sources)))
    (fail! "data sources must be a bounded map with short keyword names"))
  (doseq [[source rows] sources]
    (when-not (and (vector? rows) (<= 1 (count rows) max-rows))
      (fail! (str "data source " (pr-str source)
                  " must contain from 1 to 512 rows")))
    (doseq [row rows] (row! row)))
  sources)
(defn- source-rows! [sources {:keys [source select]}]
  (when-not (contains? sources source)
    (fail! (str "data source " (pr-str source) " is not available")))
  (let [rows (get sources source)]
    (mapv
     (fn [row]
       (doseq [field select]
         (when-not (contains? row field)
           (fail! (str "selected field " (pr-str field)
                       " is missing from data source " (pr-str source)))))
       (row! (select-keys row select)))
     rows)))
(defn- rows! [data sources]
  (if (vector? data)
    data
    (source-rows! sources (data-reference! data))))

(defn referenced-data
  "Return the validated data reference in bounded Plotje text, or nil for literal data."
  [text]
  (let [text (str (or text ""))]
    (when (> (count text) max-spec-chars) (fail! "chart spec is too large"))
    (when (str/blank? text) (fail! "chart spec is empty"))
    (try
      (let [value (edn/read-string
                   {:readers {}
                    :default (fn [tag _]
                               (fail! (str "tagged literal " tag
                                           " is not allowed")))}
                   text)
            data (when (map? value) (:data value))]
        (when (map? data) (data-reference! data)))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable _ (fail! "chart spec is not valid EDN")))))

(defn referenced-source [text]
  (some-> (referenced-data text) :source))
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
      (fail! "layer mark must be one of :line, :point, :bar, :area, :rule, or :tick"))
    (when-not (every? #(finite? (get % y)) rows)
      (fail! "layer y column must contain finite numbers"))
    (when (and (#{:line :point :area :tick} mark)
               (not (every? #(finite? (get % x)) rows)))
      (fail! "line, point, area, and tick x columns must contain finite numbers"))
    (cond-> {:mark mark :x x :y y}
      color (assoc :color color)
      (contains? layer :stroke) (assoc :stroke (color! :stroke (:stroke layer)))
      (contains? layer :fill) (assoc :fill (color! :fill (:fill layer)))
      (contains? layer :opacity)
      (assoc :opacity (bounded-number! :opacity (:opacity layer) 0.0 1.0))
      (contains? layer :stroke-width)
      (assoc :stroke-width
             (bounded-number! :stroke-width (:stroke-width layer) 0.5 10.0))
      (contains? layer :point-radius)
      (assoc :point-radius
             (bounded-number! :point-radius (:point-radius layer) 1.0 16.0))
      (contains? layer :bar-width)
      (assoc :bar-width
             (bounded-number! :bar-width (:bar-width layer) 0.1 1.0)))))

(defn validate-spec
  ([value] (validate-spec value {}))
  ([value sources]
   (when-not (map? value) (fail! "the chart spec must be an EDN map"))
   (unknown! "chart spec" top-keys value)
   (let [sources (validate-data-sources sources)
         rows (rows! (:data value) sources) layers (:layers value)]
    (when-not (and (vector? rows) (<= 1 (count rows) max-rows))
      (fail! "data must be a vector containing from 1 to 512 rows"))
    (doseq [row rows] (row! row))
    (when-not (and (vector? layers) (<= 1 (count layers) max-layers))
      (fail! "layers must contain from 1 to 4 entries"))
    (cond-> {:data rows
             :layers (mapv #(layer! rows %) layers)
             :width (dim! :width (get value :width 760) 320 1200)
             :height (dim! :height (get value :height 420) 240 800)
             :grid? (if (contains? value :grid?)
                      (if (boolean? (:grid? value))
                        (:grid? value)
                        (fail! "grid? must be boolean"))
                      true)
             :palette (if (contains? value :palette)
                        (palette! (:palette value))
                        ["#e41a1c" "#377eb8" "#4daf4a" "#984ea3"])}
      (contains? value :title) (assoc :title (label! :title (:title value)))
      (contains? value :x-label) (assoc :x-label (label! :x-label (:x-label value)))
      (contains? value :y-label) (assoc :y-label (label! :y-label (:y-label value)))))))

(defn parse-spec
  ([text] (parse-spec text {}))
  ([text sources]
   (let [text (str (or text ""))]
     (when (> (count text) max-spec-chars) (fail! "chart spec is too large"))
     (when (str/blank? text) (fail! "chart spec is empty"))
     (try
       (validate-spec
        (edn/read-string
         {:readers {}
          :default (fn [tag _]
                     (fail! (str "tagged literal " tag " is not allowed")))}
         text)
        sources)
       (catch clojure.lang.ExceptionInfo error (throw error))
       (catch Throwable _ (fail! "chart spec is not valid EDN"))))))
