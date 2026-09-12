(ns oscope.ui.web
  "Zero-JavaScript HTML renderer and embeddable Ring adapter."
  (:require [clojure.string :as str]
            [jolt.http.body :as http-body]
            [oscope.command :as command]
            [oscope.plotje.svg :as plotje]
            [oscope.query :as query]
            [oscope.typed-query :as typed-query]
            [oscope.raw-export :as raw-export]
            [oscope.sample :as sample])
  (:import [java.net URLDecoder URLEncoder]))

(def default-path "/oscope")
(def path default-path)
(def html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   (str "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; "
        "connect-src 'self'; form-action 'self'; base-uri 'none'")
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"})
(defn- esc [value]
  (-> (str (or value "")) (str/replace "&" "&amp;")
      (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))
(defn parse-query-params [query-string]
  (if (> (count (or query-string "")) 4096)
    {}
    (try
      (reduce
       (fn [params pair]
         (let [index (str/index-of pair "=")
               raw-key (if index (subs pair 0 index) pair)
               raw-value (if index (subs pair (inc index)) "")
               key (URLDecoder/decode raw-key "UTF-8")
               value (URLDecoder/decode raw-value "UTF-8")]
           (if (or (contains? params key) (> (count value) 256))
             params (assoc params key value))))
       {} (remove str/blank? (str/split (or query-string "") #"&")))
      (catch Throwable _ {}))))
(defn- keyword-param [params key allowed fallback]
  (or (some #(when (= (get params key) (name %)) %) allowed) fallback))
(def ^:private serialized-typed-binding-params
  #{"typed-attribute-key" "typed-attribute-type" "typed-manifest-version"})
(defn- serialized-typed-binding? [params]
  (let [present (set (filter #(contains? params %)
                             serialized-typed-binding-params))]
    (when-not (or (empty? present)
                  (= serialized-typed-binding-params present))
      (throw (ex-info "typed schema binding parameters must be complete"
                      {:oscope.ui/error true})))
    (seq present)))
(defn- manifest-version-param [params]
  (let [raw (get params "typed-manifest-version")]
    (when-not (and (string? raw) (re-matches #"[0-9]{1,19}" raw))
      (throw (ex-info "invalid typed manifest version"
                      {:oscope.ui/error true})))
    (try
      (let [version (parse-long raw)]
        (if (and (integer? version)
                 (<= 1 version typed-query/int64-max))
          version
          (throw (ex-info "invalid typed manifest version"
                          {:oscope.ui/error true}))))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable _
        (throw (ex-info "invalid typed manifest version"
                        {:oscope.ui/error true}))))))
(defn- typed-binding-from-params [params typed-span-fields]
  (let [field-id (get params "typed-field-id")
        serialized? (serialized-typed-binding? params)
        binding
        (if serialized?
          {:field-id field-id
           :attribute-key (get params "typed-attribute-key")
           :attribute-type (keyword-param params "typed-attribute-type"
                                          (:types typed-query/filter-capability)
                                          nil)
           :manifest-version (manifest-version-param params)}
          (typed-query/resolve-field-id typed-span-fields field-id))]
    (typed-query/resolve-binding typed-span-fields binding)))
(defn- typed-int64-param [params parameter]
  (let [raw (get params parameter)]
    (when-not (and (string? raw) (re-matches #"-?[0-9]{1,20}" raw))
      (throw (ex-info "invalid typed Int64 value" {:oscope.ui/error true})))
    (try
      (let [parsed (parse-long raw)]
        (if (typed-query/int64? parsed)
          parsed
          (throw (ex-info "invalid typed Int64 value"
                          {:oscope.ui/error true}))))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Throwable _
        (throw (ex-info "invalid typed Int64 value"
                        {:oscope.ui/error true}))))))
(defn selection-from-params
  ([params] (selection-from-params params nil))
  ([params typed-span-fields]
  (let [mode (case (get params "mode")
               "typed-span-filter" :typed-span-filter
               "typed-span-int64-aggregate" :typed-span-int64-aggregate
               "metric-series" :metric-series
               "counter-series" :counter-series
               "cumulative-histogram-series" :cumulative-histogram-series
               :distribution)
        typed-filter? (= :typed-span-filter mode)
        typed-aggregate? (= :typed-span-int64-aggregate mode)
        series? (contains? #{:metric-series :counter-series
                             :cumulative-histogram-series} mode)
        counter? (= :counter-series mode)
        histogram? (= :cumulative-histogram-series mode)
        defaults (case mode
                   :metric-series query/default-metric-series-selection
                   :counter-series query/default-counter-series-selection
                   :cumulative-histogram-series
                   query/default-cumulative-histogram-series-selection
                   query/default-selection)
        window (keyword-param params "window" (keys query/windows) (:window defaults))
        raw-limit (get params "limit")
        parsed (when (and (string? raw-limit) (re-matches #"[0-9]{1,3}" raw-limit))
                 (parse-long raw-limit))
        limit (if (and parsed (<= 1 parsed query/max-result-limit))
                parsed (:limit defaults))]
    (cond
      typed-aggregate?
      (let [binding (typed-binding-from-params params typed-span-fields)
            _ (when (and (contains? params "typed-gte")
                         (str/blank? (get params "typed-gte")))
                (throw (ex-info "invalid typed Int64 aggregate lower bound"
                                {:oscope.ui/error true})))
            predicate (cond-> {}
                        (not (str/blank? (get params "typed-gte")))
                        (assoc :gte (typed-int64-param params "typed-gte"))
                        (not (str/blank? (get params "typed-lt")))
                        (assoc :lt (typed-int64-param params "typed-lt")))
            predicate (if (seq predicate) predicate {:gte typed-query/int64-min})
            group (case (get params "group-by")
                    nil :none
                    "none" :none
                    "service-name" :service-name
                    (throw (ex-info "invalid typed Int64 aggregate group"
                                    {:oscope.ui/error true})))
            supported (:aggregates typed-query/int64-aggregate-capability)
            selected (vec (filter #(= "1" (get params (str "aggregate-" (name %))))
                                  supported))
            aggregates (if (= "1" (get params "aggregates-present"))
                         selected supported)]
        (query/normalize-selection
         {:mode :typed-span-int64-aggregate
          :schema-binding binding :predicate predicate
          :group-by (if (= :none group) [] [group])
          :aggregates aggregates :window window :limit limit}))

      typed-filter?
      (let [binding (typed-binding-from-params params typed-span-fields)
            allowed-operators (typed-query/operators-for (:attribute-type binding))
            raw-operator (get params "typed-operator")
            operator (if (nil? raw-operator)
                       :eq
                       (some #(when (= raw-operator (name %)) %)
                             allowed-operators))
            raw-value (get params "typed-value" "")
            value (case (:attribute-type binding)
                    :boolean
                    (case raw-value "true" true "false" false
                          (throw (ex-info "invalid typed Boolean filter value"
                                          {:oscope.ui/error true})))
                    :int64
                    (when (re-matches #"-?[0-9]{1,20}" raw-value)
                      (try
                        (let [parsed (parse-long raw-value)]
                          (when (typed-query/int64? parsed) parsed))
                        (catch Throwable _ nil)))
                    raw-value)]
        (when (nil? operator)
          (throw (ex-info "invalid typed filter operator"
                          {:oscope.ui/error true})))
        (when (and (= :int64 (:attribute-type binding)) (nil? value))
          (throw (ex-info "invalid typed Int64 filter value"
                          {:oscope.ui/error true})))
        (query/normalize-selection
         {:mode :typed-span-filter :schema-binding binding :operator operator
          :value value :window window :limit limit}))

      series?
      (let [options (cond counter? query/counter-series-options
                          histogram? query/cumulative-histogram-series-options
                          :else query/metric-series-options)
            kind (keyword-param params "metric-kind" (:metric-kinds options)
                                (:metric-kind defaults))
            allowed-aggregates (if (or counter? histogram?) (:aggregates options)
                                   (get-in options [:aggregates kind]))
            selected-group
            (keyword-param params "group-by"
                           (into [:none] (:group-by options))
                           (or (first (:group-by defaults)) :none))
            selected-aggregates
            (vec (filter #(= "1" (get params (str "aggregate-" (name %))))
                         allowed-aggregates))
            fallback-aggregates (vec (filter (set allowed-aggregates)
                                             (:aggregates defaults)))
            requested-aggregates
            (if (= "1" (get params "aggregates-present"))
              (if (seq selected-aggregates)
                selected-aggregates [(first allowed-aggregates)])
              (if (seq fallback-aggregates)
                fallback-aggregates [(first allowed-aggregates)]))
            requested-aggregates
            (if (and histogram?
                     (some #(contains? #{:avg :p50 :p95 :p99} %)
                           requested-aggregates))
              (vec (filter (conj (set requested-aggregates) :count)
                           allowed-aggregates))
              requested-aggregates)]
        (query/normalize-selection
         (cond-> {:mode mode
          :metric-kind kind
          :metric-name (or (not-empty (get params "metric-name"))
                           (:metric-name defaults))
          :group-by (if (= :none selected-group) [] [selected-group])
          :bucket (keyword-param params "bucket" (:buckets options)
                                 (:bucket defaults))
          :aggregates requested-aggregates
          :window window :limit limit}
           counter? (assoc :temporality :cumulative :monotonic? true)
           histogram? (assoc :temporality :cumulative))))
      :else
      (let [supported (query/supported-fields)
            signal (keyword-param params "signal" (keys supported)
                                  (:signal defaults))
            fields (get supported signal)
            field (keyword-param params "field" fields
                                 (or (some #{(:field defaults)} fields)
                                     (first fields)))]
        (query/normalize-selection
         {:signal signal :field field :window window :limit limit}))))))
(defn- option [value label selected?]
  (str "<option value=\"" (esc (name value)) "\""
       (when selected? " selected") ">" (esc label) "</option>"))
(defn- route-path [value]
  (when-not (and (string? value)
                 (<= 1 (count value) 200)
                 (re-matches #"/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)?" value))
    (throw (ex-info "oscope web path must be an absolute URL path"
                    {:oscope.ui/error true :type ::invalid-path :path value})))
  value)
(defn export-path [path]
  (let [path (route-path path)]
    (if (= path "/") "/export" (str path "/export"))))
(defn refresh-path [path]
  (let [path (route-path path)]
    (if (= path "/") "/refresh" (str path "/refresh"))))
(defn live-asset-path [path]
  (let [path (route-path path)]
    (if (= path "/") "/live.js" (str path "/live.js"))))
(defn handled-path? [path candidate]
  (contains? #{(route-path path) (export-path path) (refresh-path path)
               (live-asset-path path)}
             candidate))
(defn- checkbox [name label checked?]
  (str "<label class=\"live-choice\"><input name=\"" (esc name)
       "\" type=\"checkbox\" value=\"1\"" (when checked? " checked")
       "> " (esc label) "</label>"))
(defn- render-distribution-controls
  [{:keys [signals windows limit]} {:keys [field]} action live?]
  (let [selected-signal (some #(when (:selected? %) %) signals)]
    (str "<form method=\"get\" action=\"" (esc action)
         "\" aria-label=\"Telemetry query\">"
         "<div class=\"controls\"><label>Query<select name=\"mode\">"
         (option :distribution "Distribution" true)
         (option :metric-series "Metric series" false)
         (option :counter-series "Counter increase/rate" false)
         (option :cumulative-histogram-series "Cumulative histogram" false)
         "</select></label><label>Signal<select name=\"signal\">"
         (apply str (map #(option (:value %) (:label %) (:selected? %)) signals))
         "</select></label><label>Group by<select name=\"field\">"
         (apply str (map #(option (:value %) (:label %) (= field (:value %)))
                         (:fields selected-signal)))
         "</select></label><label>Window<select name=\"window\">"
         (apply str (map #(option (:value %) (:label %) (:selected? %)) windows))
         "</select></label><label>Maximum rows<input name=\"limit\" type=\"number\" min=\""
         (:minimum limit) "\" max=\"" (:maximum limit) "\" value=\""
         (:value limit) "\"></label><label class=\"live-choice\"><input name=\"live\" type=\"checkbox\" value=\"1\""
         (when live? " checked")
         "> Live refresh</label><button type=\"submit\">Run query</button></div></form>")))
(defn- render-series-controls
  [controls selection action live?]
  (let [{:keys [mode metric-kind metric-name group-by bucket aggregates window]}
        selection
        counter? (= :counter-series mode)
        histogram? (= :cumulative-histogram-series mode)
        metric-kinds (:metric-kinds controls)
        available-groups (:group-by controls)
        buckets (:buckets controls)
        available-aggregates (:aggregates controls)
        windows (:windows controls)
        limit (:limit controls)]
    (str "<form method=\"get\" action=\"" (esc action)
         "\" aria-label=\"Metric series query\">"
         "<div class=\"controls series-controls\"><label>Query<select name=\"mode\">"
         (option :distribution "Distribution" false)
         (option :metric-series "Metric series" (= :metric-series mode))
         (option :counter-series "Counter increase/rate" counter?)
         (option :cumulative-histogram-series "Cumulative histogram" histogram?)
         "</select></label><label>Metric kind<select name=\"metric-kind\">"
         (apply str (map #(option % (str/capitalize (name %)) (= % metric-kind))
                         metric-kinds))
         "</select></label>"
         (when counter?
           "<input type=\"hidden\" name=\"temporality\" value=\"cumulative\"><input type=\"hidden\" name=\"monotonic\" value=\"true\"><p>Cumulative monotonic OTEL Sum · rate per second over exact observed intervals</p>")
         (when histogram?
           "<input type=\"hidden\" name=\"temporality\" value=\"cumulative\"><p>Cumulative OTEL explicit histogram · reset-aware bucket reconstruction · infinite tails have no numeric estimate</p>")
         "<label>Exact metric name<input required name=\"metric-name\" maxlength=\"256\" value=\""
         (esc metric-name) "\"></label><label>Time bucket<select name=\"bucket\">"
         (apply str (map #(option % (name %) (= % bucket)) buckets))
         "</select></label><label>Window<select name=\"window\">"
         (apply str (map #(option % (name %) (= % window)) windows))
         "</select></label><label>Maximum rows<input name=\"limit\" type=\"number\" min=\""
         (:minimum limit) "\" max=\"" (:maximum limit) "\" value=\""
         (:value limit) "\"></label><label>Group dimension<select name=\"group-by\">"
         (option :none "None" (empty? group-by))
         (apply str (map #(option %
                                 (-> % name (str/replace "-" " ") str/capitalize)
                                 (= % (first group-by)))
                         available-groups))
         "</select></label></div><fieldset><legend>Aggregate fields</legend><input type=\"hidden\" name=\"aggregates-present\" value=\"1\"><div class=\"choices\">"
         (apply str (map #(checkbox (str "aggregate-" (name %))
                                    (str/upper-case (name %))
                                    (some #{%} aggregates))
                         available-aggregates))
         "</div></fieldset><div class=\"form-actions\">"
         (checkbox "live" "Live refresh" live?)
         "<button type=\"submit\">Run query</button></div></form>")))
(defn- render-controls [controls selection action live?]
  (cond
    (contains? #{:typed-span-filter :typed-span-int64-aggregate}
               (:mode selection)) ""
    (contains? #{:metric-series :counter-series
                 :cumulative-histogram-series} (:mode selection))
    (render-series-controls controls selection action live?)
    :else (render-distribution-controls controls selection action live?)))

(defn- render-typed-controls [controls selection action]
  (let [fields (:typed-span-fields controls)
        mode (:mode selection)]
    (when (seq fields)
      (let [filter-forms
            (apply str
                   (for [type (:types typed-query/filter-capability)
                         :let [candidates
                               (filterv #(= type (:attribute-type %)) fields)]
                         :when (seq candidates)]
                     (let [current (when (= :typed-span-filter mode)
                                     (:schema-binding selection))
                           selected (or (some #(when (= current %) %) candidates)
                                        (first candidates))]
                       (str "<form method=\"get\" action=\"" (esc action)
                            "\" aria-label=\"Typed " (name type) " span filter\">"
                            "<input type=\"hidden\" name=\"mode\" value=\"typed-span-filter\">"
                            "<div class=\"controls\"><label>Typed span attribute<select name=\"typed-field-id\">"
                            (apply str (map #(option (:field-id %) (:attribute-key %)
                                                    (= selected %)) candidates))
                            "</select></label><label>Operator<select name=\"typed-operator\">"
                            (apply str
                                   (map #(option % (name %)
                                                 (= % (or (:operator selection) :eq)))
                                        (get-in typed-query/filter-capability
                                                [:operators type])))
                            "</select></label><label>Value"
                            (case type
                              :boolean
                              (str "<select name=\"typed-value\">"
                                   (option :true "true"
                                           (not= false (:value selection)))
                                   (option :false "false"
                                           (= false (:value selection)))
                                   "</select>")
                              :int64
                              (str "<input name=\"typed-value\" inputmode=\"numeric\" pattern=\"-?[0-9]+\" maxlength=\"20\" value=\""
                                   (esc (when (= :int64
                                                 (:attribute-type current))
                                          (:value selection))) "\">")
                              (str "<input name=\"typed-value\" maxlength=\"256\" value=\""
                                   (esc (when (= :string type)
                                          (:value selection))) "\">"))
                            "</label><label>Window<select name=\"window\">"
                            (apply str
                                   (map #(option % (name %)
                                                 (= % (:window selection)))
                                        [:15m :1h :6h :24h]))
                            "</select></label><label>Maximum rows<input name=\"limit\" type=\"number\" min=\"1\" max=\"100\" value=\""
                            (or (:limit selection) 12)
                            "\"></label><button type=\"submit\">Filter typed spans</button></div></form>"))))
            int64-fields (filterv #(= :int64 (:attribute-type %)) fields)
            aggregate-form
            (when (seq int64-fields)
              (let [active? (= :typed-span-int64-aggregate mode)
                    current (when active? (:schema-binding selection))
                    selected (or (some #(when (= current %) %) int64-fields)
                                 (first int64-fields))
                    predicate (if active? (:predicate selection)
                                  {:gte typed-query/int64-min})
                    aggregates (if active? (:aggregates selection)
                                   (:aggregates
                                    typed-query/int64-aggregate-capability))]
                (str "<form method=\"get\" action=\"" (esc action)
                     "\" aria-label=\"Typed Int64 span aggregate\">"
                     "<input type=\"hidden\" name=\"mode\" value=\"typed-span-int64-aggregate\">"
                     "<div class=\"controls\"><label>Typed Int64 attribute<select name=\"typed-field-id\">"
                     (apply str (map #(option (:field-id %) (:attribute-key %)
                                             (= selected %)) int64-fields))
                     "</select></label><label>Minimum, inclusive<input required name=\"typed-gte\" inputmode=\"numeric\" pattern=\"-?[0-9]+\" maxlength=\"20\" value=\""
                     (esc (:gte predicate))
                     "\"></label><label>Maximum, exclusive<input name=\"typed-lt\" inputmode=\"numeric\" pattern=\"-?[0-9]+\" maxlength=\"20\" value=\""
                     (esc (:lt predicate))
                     "\"></label><label>Group by<select name=\"group-by\">"
                     (option :none "None" (empty? (:group-by selection)))
                     (option :service-name "Service name"
                             (= [:service-name] (:group-by selection)))
                     "</select></label><label>Window<select name=\"window\">"
                     (apply str (map #(option % (name %)
                                             (= % (:window selection)))
                                     [:15m :1h :6h :24h]))
                     "</select></label><label>Maximum groups<input name=\"limit\" type=\"number\" min=\"1\" max=\"100\" value=\""
                     (or (:limit selection) 12)
                     "\"></label></div><fieldset><legend>Typed aggregate fields</legend>"
                     "<input type=\"hidden\" name=\"aggregates-present\" value=\"1\"><div class=\"choices\">"
                     (apply str
                            (map #(checkbox (str "aggregate-" (name %))
                                            (str/upper-case (name %))
                                            (some #{%} aggregates))
                                 (:aggregates
                                  typed-query/int64-aggregate-capability)))
                     "</div></fieldset><div class=\"form-actions\"><button type=\"submit\">Summarize typed values</button></div></form>")))]
        (str filter-forms aggregate-form
             (when (:typed-span-fields-truncated? controls)
               (str "<p>Showing " (count fields) " of "
                    (:typed-span-field-total controls)
                    " approved typed fields.</p>")))))))
(defn selection-query-string [selection]
  (let [selection (query/normalize-selection selection)]
    (cond
      (= :typed-span-int64-aggregate (:mode selection))
      (let [{:keys [field-id attribute-key attribute-type manifest-version]}
            (:schema-binding selection)
            predicate (:predicate selection)]
        (str "?mode=typed-span-int64-aggregate&typed-field-id="
             (URLEncoder/encode field-id "UTF-8")
             "&typed-attribute-key=" (URLEncoder/encode attribute-key "UTF-8")
             "&typed-attribute-type=" (name attribute-type)
             "&typed-manifest-version=" manifest-version
             (when (contains? predicate :gte)
               (str "&typed-gte=" (:gte predicate)))
             (when (contains? predicate :lt)
               (str "&typed-lt=" (:lt predicate)))
             "&group-by=" (name (or (first (:group-by selection)) :none))
             "&aggregates-present=1"
             (apply str (map #(str "&aggregate-" (name %) "=1")
                             (:aggregates selection)))
             "&window=" (name (:window selection))
             "&limit=" (:limit selection)))

      (= :typed-span-filter (:mode selection))
      (let [{:keys [field-id attribute-key attribute-type manifest-version]}
            (:schema-binding selection)]
        (str "?mode=typed-span-filter&typed-field-id=" (URLEncoder/encode field-id "UTF-8")
             "&typed-attribute-key=" (URLEncoder/encode attribute-key "UTF-8")
             "&typed-attribute-type=" (name attribute-type)
             "&typed-manifest-version=" manifest-version
             "&typed-operator=" (name (:operator selection))
             "&typed-value=" (URLEncoder/encode (str (:value selection)) "UTF-8")
             "&window=" (name (:window selection)) "&limit=" (:limit selection)))
      (contains? #{:metric-series :counter-series
                     :cumulative-histogram-series} (:mode selection))
      (str "?mode=" (name (:mode selection))
           "&metric-kind=" (name (:metric-kind selection))
           (when (= :counter-series (:mode selection))
             "&temporality=cumulative&monotonic=true")
           (when (= :cumulative-histogram-series (:mode selection))
             "&temporality=cumulative")
           "&metric-name=" (URLEncoder/encode (:metric-name selection) "UTF-8")
           "&bucket=" (name (:bucket selection))
           "&group-by=" (name (or (first (:group-by selection)) :none))
           "&aggregates-present=1"
           (apply str (map #(str "&aggregate-" (name %) "=1")
                           (:aggregates selection)))
           "&window=" (name (:window selection))
           "&limit=" (:limit selection))
      :else
      (str "?signal=" (name (:signal selection))
           "&field=" (name (:field selection))
           "&window=" (name (:window selection))
           "&limit=" (:limit selection)))))
(defn- raw-typed-request? [params]
  (and (contains? #{"typed-span-filter" "typed-span-int64-aggregate"}
                  (get params "mode"))
       (not-any? #(contains? params %) serialized-typed-binding-params)))
(defn- canonical-typed-location [path selection live?]
  (str path (selection-query-string selection) (when live? "&live=1")))
(defn- render-export-controls [screen action enabled?]
  (let [{:keys [signal mode metric-kind]} (:selection screen)
        series? (contains? #{:metric-series :counter-series
                             :cumulative-histogram-series} mode)
        signal (cond series? :metrics
                     (contains? #{:typed-span-filter
                                  :typed-span-int64-aggregate} mode) :spans
                     :else signal)
        metric-kind (if series? metric-kind :gauge)
        {:keys [start-unix-nano end-unix-nano]}
        (get-in screen [:query-plan :request])]
    (str "<section aria-labelledby=\"export-title\"><h2 id=\"export-title\">Export raw telemetry</h2>"
         "<p>Download physical rows from this absolute, half-open time window. "
         "Exports are capped at 24 hours, 100,000 rows, and 64 MiB.</p>"
         (when-not enabled?
           "<p role=\"status\">Raw export is unavailable in sample mode.</p>")
         "<form method=\"get\" action=\"" (esc action)
         "\" aria-label=\"Raw telemetry export\" data-oscope-export-form><fieldset"
         (when-not enabled? " disabled") "><legend>Bounded data export</legend>"
         "<div class=\"export-controls\"><label>Signal<select name=\"signal\">"
         (option :spans "Spans" (= signal :spans))
         (option :logs "Logs" (= signal :logs))
         (option :metrics "Metrics" (= signal :metrics))
         "</select></label><label>Metric kind<select name=\"metric-kind\">"
         (option :gauge "Gauge" (= :gauge metric-kind))
         (option :sum "Sum" (= :sum metric-kind))
         (option :histogram "Histogram" (= :histogram metric-kind))
         "</select></label>"
         "<label>Format<select name=\"format\">"
         (option :parquet "Parquet" true) (option :arrow "Arrow" false)
         "</select></label>"
         "<label>Start (Unix ns)<input required name=\"start-unix-nano\" data-oscope-export-start type=\"number\" min=\"0\" value=\""
         start-unix-nano "\"></label>"
         "<label>End, exclusive (Unix ns)<input required name=\"end-unix-nano\" data-oscope-export-end type=\"number\" min=\"1\" value=\""
         end-unix-nano "\"></label>"
         "<label>Maximum rows<input required name=\"max-rows\" type=\"number\" min=\"1\" max=\""
         raw-export/max-result-rows "\" value=\"" raw-export/default-max-rows "\"></label>"
         "<label>Maximum bytes<input required name=\"max-bytes\" type=\"number\" min=\"1\" max=\""
         raw-export/max-result-bytes "\" value=\"" raw-export/default-max-bytes "\"></label>"
         "<button type=\"submit\">Download</button></div></fieldset></form></section>")))
(defn- render-table [{:keys [columns rows]}]
  (str "<div class=\"table-wrap\"><table><caption>Bounded query results</caption>"
       "<thead><tr>" (apply str (map #(str "<th scope=\"col\">" (esc (:label %)) "</th>") columns))
       "</tr></thead><tbody>"
       (apply str
              (map (fn [row]
                     (str "<tr>"
                          (apply str
                                 (map-indexed
                                  (fn [index {:keys [key]}]
                                    (let [tag (if (zero? index) "th" "td")]
                                      (str "<" tag
                                           (when (zero? index) " scope=\"row\"")
                                           ">" (esc (get row key)) "</" tag ">")))
                                  columns))
                          "</tr>"))
                   rows))
       "</tbody></table></div>"))
(def ^:private style
  (str ":root{color-scheme:dark;--bg:#0d1219;--panel:#182231;--text:#f7f9fc;"
       "--muted:#c4cfdd;--line:#52647c;--accent:#8bd3ff}*{box-sizing:border-box}"
       "body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}"
       "header,main{width:min(1180px,calc(100% - 2rem));margin:auto}header{padding:1.25rem 0 .5rem}"
       "a{color:var(--accent)}p{color:var(--muted)}form,.panel{background:var(--panel);border:1px solid var(--line);border-radius:.7rem;padding:1rem;margin:1rem 0}"
       ".controls{display:grid;grid-template-columns:repeat(4,minmax(9rem,1fr)) auto;gap:.7rem;align-items:end}"
       "label{font-weight:700}select,input{display:block;width:100%;margin-top:.25rem;background:#090e15;color:var(--text);border:1px solid #7589a3;border-radius:.4rem;padding:.55rem}"
       "button{background:#d8f1ff;color:#071018;border:0;border-radius:.4rem;padding:.65rem 1rem;font-weight:800}"
       "button:disabled,fieldset:disabled{opacity:.65}fieldset{border:0;padding:0;margin:0}legend{font-weight:800;margin-bottom:.5rem}"
       ".live-choice{display:flex;gap:.45rem;align-items:center;min-height:2.7rem}.live-choice input{width:auto;margin:0}"
       ".choices,.form-actions{display:flex;gap:.8rem;align-items:center;flex-wrap:wrap}.choices .live-choice{font-weight:600}.form-actions{justify-content:flex-end;margin-top:.8rem}"
       ".screen-head{display:flex;align-items:start;justify-content:space-between;gap:1rem}.live-state{display:flex;align-items:center;gap:.65rem;flex-wrap:wrap}.provenance{font-variant-numeric:tabular-nums}"
       ".export-controls{display:grid;grid-template-columns:repeat(3,minmax(9rem,1fr));gap:.7rem;align-items:end}"
       ".grid{display:grid;grid-template-columns:minmax(20rem,1.2fr) minmax(24rem,.8fr);gap:1rem}"
       ".metric-series-grid{grid-template-columns:1fr}"
       "svg{display:block;width:100%;height:auto;background:white;border-radius:.35rem}.table-wrap{overflow:auto}"
       "table{width:100%;border-collapse:collapse}caption{text-align:left;font-weight:800;padding:0 0 .6rem}"
       "th,td{text-align:left;border-bottom:1px solid var(--line);padding:.55rem}th{white-space:nowrap}td{text-align:right;overflow-wrap:anywhere}"
       "@media(max-width:850px){.controls,.export-controls,.grid{grid-template-columns:1fr}}"))

(defn render-screen-fragment
  "Render one complete, atomically replaceable versioned query screen."
  ([screen] (render-screen-fragment screen {}))
  ([screen {:keys [live?]}]
   (let [{:keys [title chart table empty-message view coverage freshness-notice]} screen
         version (:oscope.view/version screen)
         {:keys [start-unix-nano end-unix-nano]}
         (get-in screen [:query-plan :request])]
     (str "<section id=\"oscope-screen\" data-oscope-view-version=\"" version
          "\" data-oscope-query-start=\"" start-unix-nano
          "\" data-oscope-query-end=\"" end-unix-nano "\">"
          "<div class=\"screen-head\"><div><h2>" (esc title) "</h2>"
          "<p class=\"provenance\">Exact half-open query window: <code>"
          start-unix-nano "</code> to <code>" end-unix-nano
          "</code>.</p></div>"
          (when live?
            (str "<div class=\"live-state\"><button type=\"button\" data-oscope-freeze>Freeze for export</button>"
                 "<span role=\"status\" aria-live=\"polite\" data-oscope-live-status>Live refresh is on.</span></div>"))
          "</div>"
          (when freshness-notice
            (str "<p role=\"note\">" (esc freshness-notice) "</p>"))
          (when coverage
            (str "<section class=\"panel\" aria-labelledby=\"typed-coverage-title\">"
                 "<h3 id=\"typed-coverage-title\">Typed value coverage</h3>"
                 (render-table {:columns [{:key :status :label "Status"}
                                          {:key :count :label "Rows"}]
                                :rows coverage}) "</section>"))
          (if chart
            (str "<div class=\"grid"
                 (when (contains? #{:telemetry-metric-series
                                    :telemetry-cumulative-histogram-series}
                                  view)
                   " metric-series-grid")
                 "\"><section class=\"panel\">"
                 (plotje/spec->svg chart) "</section><section class=\"panel\">"
                 (render-table table) "</section></div>")
            (str "<section class=\"panel\""
                 (when empty-message " role=\"status\"") ">"
                 (when empty-message (str "<p>" (esc empty-message) "</p>"))
                 (render-table table) "</section>"))
          "</section>"))))

(def ^:private live-script
  (str
   "(() => {'use strict';"
   "const root=document.querySelector('[data-oscope-live-root]');if(!root)return;"
   "const baseIntervalMs=2000,maxBackoffMs=30000;"
   "let live=true,inFlight=false,timer=null,controller=null,generation=0,backoffMs=baseIntervalMs;"
   "const status=()=>document.querySelector('[data-oscope-live-status]');"
   "const announce=(message)=>{const node=status();if(node)node.textContent=message;};"
   "const clearTimer=()=>{if(timer!==null){clearTimeout(timer);timer=null;}};"
   "const cancelPending=()=>{generation+=1;clearTimer();if(controller)controller.abort();controller=null;inFlight=false;};"
   "const schedule=(delay)=>{clearTimer();if(live&&!document.hidden)timer=setTimeout(refresh,delay);};"
   "const refreshUrl=()=>{const url=new URL(root.dataset.oscopeRefreshPath,location.origin);url.search=location.search;url.searchParams.delete('live');return url;};"
   "async function refresh(){"
   "if(inFlight||!live||document.hidden)return;inFlight=true;const mine=++generation;"
   "const requestController=new AbortController();controller=requestController;"
   "try{const response=await fetch(refreshUrl(),{headers:{Accept:'text/html'},cache:'no-store',signal:requestController.signal});"
   "if(!response.ok)throw new Error('refresh failed');const text=await response.text();"
   "const parsed=new DOMParser().parseFromString(text,'text/html');"
   "const next=parsed.querySelector('#oscope-screen[data-oscope-view-version=\"1\"]');"
   "const current=document.querySelector('#oscope-screen[data-oscope-view-version=\"1\"]');"
   "if(!next||!current)throw new Error('invalid refresh fragment');"
   "if(!live||mine!==generation)return;current.replaceWith(document.importNode(next,true));"
   "backoffMs=baseIntervalMs;announce('Live refresh is on.');"
   "}catch(error){if(error.name!=='AbortError'&&live){backoffMs=Math.min(backoffMs*2,maxBackoffMs);announce('Refresh delayed; retrying.');}}"
   "finally{if(controller===requestController){controller=null;inFlight=false;}if(live)schedule(backoffMs);}}"
   "const freeze=()=>{const screen=document.querySelector('#oscope-screen[data-oscope-view-version=\"1\"]');"
   "const form=document.querySelector('[data-oscope-export-form]');if(!screen||!form)return;"
   "live=false;cancelPending();form.querySelector('[data-oscope-export-start]').value=screen.dataset.oscopeQueryStart;"
   "form.querySelector('[data-oscope-export-end]').value=screen.dataset.oscopeQueryEnd;"
   "const choice=document.querySelector('input[name=\"live\"]');if(choice)choice.checked=false;"
   "const button=screen.querySelector('[data-oscope-freeze]');if(button){button.disabled=true;button.textContent='Frozen';}"
   "announce('Frozen exact window for export.');const url=new URL(location.href);url.searchParams.delete('live');history.replaceState(null,'',url);};"
   "document.addEventListener('click',(event)=>{if(event.target.closest('[data-oscope-freeze]')){event.preventDefault();freeze();}});"
   "document.addEventListener('visibilitychange',()=>{if(document.hidden)cancelPending();else if(live)schedule(0);});"
   "schedule(baseIntervalMs);})();"))

(defn- live-asset-response []
  {:status 200
   :headers {"Content-Type" "text/javascript; charset=UTF-8"
             "Cache-Control" "no-store"
             "Referrer-Policy" "no-referrer"
             "X-Content-Type-Options" "nosniff"}
   :body live-script})

(defn render-page
  ([screen] (render-page screen {}))
  ([screen {:keys [path export-enabled? live? visualization-editor-path]
            :or {path default-path export-enabled? false live? false}}]
   (let [path (route-path path)
         {:keys [selection controls]} screen]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>oscope · " (esc (:title screen)) "</title><style>" style "</style>"
         (when live? (str "<script defer src=\"" (esc (live-asset-path path)) "\"></script>"))
         "</head><body>"
         "<header><h1>oscope</h1><p>Explore bounded distributions and metric series from embedded telemetry.</p>"
         (when (and visualization-editor-path (:chart screen))
           (str "<nav aria-label=\"Visualization utilities\"><a href=\""
                (esc visualization-editor-path)
                (esc (selection-query-string selection))
                "\">Edit this chart</a></nav>"))
         "</header><main"
         (when live? (str " data-oscope-live-root data-oscope-refresh-path=\""
                          (esc (refresh-path path)) "\"")) ">"
         (render-controls controls selection path live?)
         (render-typed-controls controls selection path)
         (render-screen-fragment screen {:live? live?})
         (render-export-controls screen (export-path path) export-enabled?)
         "</main></body></html>"))))
(def render-snapshot render-page)
(def render render-page)

(def ^:private export-param-keys
  #{"signal" "metric-kind" "start-unix-nano" "end-unix-nano"
    "format" "max-rows" "max-bytes"})
(defn- required-integer-param [params key]
  (let [value (get params key)]
    (when-not (and (string? value) (re-matches #"[0-9]{1,19}" value))
      (throw (ex-info "invalid export parameter"
                      {:oscope.ui/error true :parameter key})))
    (parse-long value)))
(defn export-selection-from-params [params]
  (when-not (map? params)
    (throw (ex-info "invalid export parameters" {:oscope.ui/error true})))
  (when-let [unknown (seq (remove export-param-keys (keys params)))]
    (throw (ex-info "unsupported export parameter"
                    {:oscope.ui/error true :parameters (vec (sort unknown))})))
  (let [signal (keyword-param params "signal" [:spans :logs :metrics] nil)
        metric-kind (when (= signal :metrics)
                      (keyword-param params "metric-kind"
                                     [:gauge :sum :histogram] nil))
        format (keyword-param params "format" [:arrow :parquet] nil)]
    (raw-export/normalize-selection
     {:signal signal :metric-kind metric-kind
      :start-unix-nano (required-integer-param params "start-unix-nano")
      :end-unix-nano (required-integer-param params "end-unix-nano")
      :format format
      :max-rows (required-integer-param params "max-rows")
      :max-bytes (required-integer-param params "max-bytes")})))

(defn- acquire! [{:keys [capacity active]}]
  (loop []
    (let [current @active]
      (cond
        (>= current capacity) false
        (compare-and-set! active current (inc current)) true
        :else (recur)))))

(defn- release! [{:keys [active]}]
  (loop []
    (let [current @active]
      (cond
        (zero? current) false
        (compare-and-set! active current (dec current)) true
        :else (recur)))))

(defrecord ExportBody [bytes admission released?]
  http-body/StreamableBody
  (write-body-to-sink [_ _response sink]
    (try
      (http-body/sink-write! sink bytes 0 (count bytes))
      (finally
        (when (compare-and-set! released? false true)
          (release! admission))))))

(defn release-export-body!
  "Idempotently release an export response that a host elects not to write. A
  normal jolt-http response releases itself after its final write or failure."
  [body]
  (when (and (instance? ExportBody body)
             (compare-and-set! (:released? body) false true))
    (release! (:admission body)))
  nil)

(defn- binary-response [result admission]
  (let [result (raw-export/validate-result result)
        {:keys [max-bytes] :as selection} (:selection result)
        {:keys [content-type suggested-filename]}
        (raw-export/response-metadata selection)
        byte-count (:byte-count result)
        bytes (:bytes result)]
    (when-not (and (integer? byte-count) (<= 0 byte-count max-bytes)
                   (bytes? bytes) (= byte-count (count bytes)))
      (throw (ex-info "invalid oscope export result"
                      {:oscope.ui/error true :byte-count byte-count})))
    {:status 200
     :headers {"Content-Type" content-type
               "Content-Disposition"
               (str "attachment; filename=\"" suggested-filename "\"")
               "Content-Length" (str byte-count)
               "Cache-Control" "no-store"
               "Referrer-Policy" "no-referrer"
               "X-Content-Type-Options" "nosniff"}
     :body (->ExportBody bytes admission (atom false))}))

(def ^:private text-headers
  {"Content-Type" "text/plain; charset=UTF-8"
   "Cache-Control" "no-store"
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"})

(defn- header-value [request name]
  (let [headers (or (:headers request) {})]
    (or (get headers name) (get headers (str/lower-case name)))))

(defn- same-origin-export? [request]
  (not= "cross-site"
        (some-> (header-value request "Sec-Fetch-Site") str/lower-case)))

(defn handler
  "Return a Ring handler backed by one open oscope source.

  It returns nil outside its configured path, never owns/closes the source,
  and routes every GET through the versioned command/effect seam."
  ([source] (handler source {}))
  ([source {:keys [path authorize-export? visualization-editor-path]
            :or {path default-path authorize-export? same-origin-export?}}]
   (let [path (route-path path)
         export-path (export-path path)
         refresh-path (refresh-path path)
         live-asset-path (live-asset-path path)
         admission (or (:export-admission source)
                       {:capacity 1 :active (atom 0)})]
     (fn [{:keys [request-method uri query-string query-params] :as request}]
       (try
        (cond
        (= live-asset-path uri)
        (if (= :get request-method)
          (live-asset-response)
          {:status 405 :headers (assoc text-headers "Allow" "GET")
           :body "method not allowed"})

        (= refresh-path uri)
        (if (not= :get request-method)
          {:status 405 :headers (assoc html-headers "Allow" "GET")
           :body "method not allowed"}
          (let [params (or query-params (parse-query-params query-string))
                selection (selection-from-params params (:typed-span-fields source))
                screen ((:load-command source)
                        [:web-refresh (System/nanoTime)] selection)]
            {:status 200 :headers html-headers
             :body (render-screen-fragment screen {:live? true})}))

        (= path uri)
        (if (not= :get request-method)
           {:status 405 :headers (assoc html-headers "Allow" "GET")
            :body "method not allowed"}
           (let [params (or query-params (parse-query-params query-string))
                 selection (selection-from-params params (:typed-span-fields source))
                 live? (= "1" (get params "live"))]
             (if (raw-typed-request? params)
               {:status 303
                :headers (assoc html-headers "Location"
                                (canonical-typed-location path selection live?))
                :body ""}
               (let [screen ((:load-command source)
                             [:web (System/nanoTime)] selection)]
                 {:status 200 :headers html-headers
                  :body (render-page screen {:path path
                                             :live? live?
                                             :visualization-editor-path
                                             visualization-editor-path
                                             :export-enabled?
                                             (ifn? (:export-command source))})}))))

        (= export-path uri)
        (if (not= :get request-method)
          {:status 405 :headers (assoc text-headers "Allow" "GET")
           :body "method not allowed"}
          (if-not (ifn? (:export-command source))
            {:status 404 :headers text-headers
             :body "raw export is unavailable"}
            (if-not (authorize-export? request)
              {:status 403 :headers text-headers :body "raw export forbidden"}
              (try
                (let [params (or query-params (parse-query-params query-string))
                      selection (export-selection-from-params params)]
                  (if-not (acquire! admission)
                    {:status 503
                     :headers (assoc text-headers "Retry-After" "1")
                     :body "raw export capacity reached"}
                    (try
                      (binary-response
                       ((:export-command source) [:web-export (System/nanoTime)]
                        selection)
                       admission)
                      (catch java.sql.SQLException _
                        (release! admission)
                        {:status 500 :headers text-headers
                         :body "raw export failed"})
                      (catch clojure.lang.ExceptionInfo _
                        (release! admission)
                        {:status 500 :headers text-headers
                         :body "raw export failed"}))))
                (catch clojure.lang.ExceptionInfo _
                  {:status 400 :headers text-headers
                   :body "invalid raw export request"})))))

        :else nil)
        (catch clojure.lang.ExceptionInfo error
          (let [data (ex-data error)
                params (or query-params (parse-query-params query-string))
                typed-mode (get params "mode")
                typed-request? (contains? #{"typed-span-filter"
                                            "typed-span-int64-aggregate"}
                                          typed-mode)]
            (cond
              (= :oscope.typed-query/stale-binding (:type data))
              {:status 409 :headers html-headers
               :body "<!doctype html><html lang=\"en\"><body><main><h1>Typed schema changed</h1><p>The typed schema binding is no longer available. Reload and select an available field.</p></main></body></html>"}

              (and typed-request?
                   (or (:oscope.typed-query/error data)
                       (:oscope.query/error data)
                       (:oscope.ui/error data)))
              {:status 400 :headers html-headers
               :body (if (= "typed-span-int64-aggregate" typed-mode)
                       "<!doctype html><html lang=\"en\"><body><main><h1>Invalid typed aggregate</h1><p>The typed Int64 aggregate request is invalid.</p></main></body></html>"
                       "<!doctype html><html lang=\"en\"><body><main><h1>Invalid typed filter</h1><p>The typed span filter request is invalid.</p></main></body></html>")}

              :else (throw error)))))))))

(defn sample-handler []
  (handler {:load-command (fn [_ selection]
                            (sample/screen-for-selection selection))}))

(defn -main [& _] (println (render-page sample/default-screen)))
