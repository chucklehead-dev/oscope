(ns oscope.ui.web
  "Zero-JavaScript HTML renderer and embeddable Ring adapter."
  (:require [clojure.string :as str]
            [jolt.http.body :as http-body]
            [oscope.command :as command]
            [oscope.plotje.svg :as plotje]
            [oscope.query :as query]
            [oscope.raw-export :as raw-export]
            [oscope.sample :as sample])
  (:import [java.net URLDecoder]))

(def default-path "/oscope")
(def path default-path)
(def html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'"
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
           (if (or (contains? params key) (> (count value) 200))
             params (assoc params key value))))
       {} (remove str/blank? (str/split (or query-string "") #"&")))
      (catch Throwable _ {}))))
(defn- keyword-param [params key allowed fallback]
  (or (some #(when (= (get params key) (name %)) %) allowed) fallback))
(defn selection-from-params [params]
  (let [supported (query/supported-fields)
        defaults query/default-selection
        signal (keyword-param params "signal" (keys supported) (:signal defaults))
        fields (get supported signal)
        field (keyword-param params "field" fields
                             (or (some #{(:field defaults)} fields) (first fields)))
        window (keyword-param params "window" (keys query/windows) (:window defaults))
        raw-limit (get params "limit")
        parsed (when (and (string? raw-limit) (re-matches #"[0-9]{1,3}" raw-limit))
                 (parse-long raw-limit))
        limit (if (and parsed (<= 1 parsed query/max-result-limit))
                parsed (:limit defaults))]
    (query/normalize-selection
     {:signal signal :field field :window window :limit limit})))
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
(defn- render-controls [{:keys [signals windows limit]} {:keys [field]} action]
  (let [selected-signal (some #(when (:selected? %) %) signals)]
    (str "<form method=\"get\" action=\"" (esc action)
         "\" aria-label=\"Telemetry query\">"
         "<div class=\"controls\"><label>Signal<select name=\"signal\">"
         (apply str (map #(option (:value %) (:label %) (:selected? %)) signals))
         "</select></label><label>Group by<select name=\"field\">"
         (apply str (map #(option (:value %) (:label %) (= field (:value %)))
                         (:fields selected-signal)))
         "</select></label><label>Window<select name=\"window\">"
         (apply str (map #(option (:value %) (:label %) (:selected? %)) windows))
         "</select></label><label>Maximum rows<input name=\"limit\" type=\"number\" min=\""
         (:minimum limit) "\" max=\"" (:maximum limit) "\" value=\""
         (:value limit) "\"></label><button type=\"submit\">Run query</button></div></form>")))
(defn- render-export-controls [screen action enabled?]
  (let [{:keys [signal]} (:selection screen)
        {:keys [start-unix-nano end-unix-nano]}
        (get-in screen [:query-plan :request])]
    (str "<section aria-labelledby=\"export-title\"><h2 id=\"export-title\">Export raw telemetry</h2>"
         "<p>Download physical rows from this absolute, half-open time window. "
         "Exports are capped at 24 hours, 100,000 rows, and 64 MiB.</p>"
         (when-not enabled?
           "<p role=\"status\">Raw export is unavailable in sample mode.</p>")
         "<form method=\"get\" action=\"" (esc action)
         "\" aria-label=\"Raw telemetry export\"><fieldset"
         (when-not enabled? " disabled") "><legend>Bounded data export</legend>"
         "<div class=\"export-controls\"><label>Signal<select name=\"signal\">"
         (option :spans "Spans" (= signal :spans))
         (option :logs "Logs" (= signal :logs))
         (option :metrics "Metrics" (= signal :metrics))
         "</select></label><label>Metric kind<select name=\"metric-kind\">"
         (option :gauge "Gauge" true) (option :sum "Sum" false)
         (option :histogram "Histogram" false) "</select></label>"
         "<label>Format<select name=\"format\">"
         (option :parquet "Parquet" true) (option :arrow "Arrow" false)
         "</select></label>"
         "<label>Start (Unix ns)<input required name=\"start-unix-nano\" type=\"number\" min=\"0\" value=\""
         start-unix-nano "\"></label>"
         "<label>End, exclusive (Unix ns)<input required name=\"end-unix-nano\" type=\"number\" min=\"1\" value=\""
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
       (apply str (map #(str "<tr><th scope=\"row\">" (esc (:value %))
                             "</th><td>" (:count %) "</td></tr>") rows))
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
       ".export-controls{display:grid;grid-template-columns:repeat(3,minmax(9rem,1fr));gap:.7rem;align-items:end}"
       ".grid{display:grid;grid-template-columns:minmax(20rem,1.4fr) minmax(18rem,.6fr);gap:1rem}"
       "svg{display:block;width:100%;height:auto;background:white;border-radius:.35rem}.table-wrap{overflow:auto}"
       "table{width:100%;border-collapse:collapse}caption{text-align:left;font-weight:800;padding:0 0 .6rem}"
       "th,td{text-align:left;border-bottom:1px solid var(--line);padding:.55rem;overflow-wrap:anywhere}td{text-align:right}"
       "@media(max-width:850px){.controls,.export-controls,.grid{grid-template-columns:1fr}}"))

(defn render-page
  ([screen] (render-page screen {}))
  ([screen {:keys [path export-enabled?]
            :or {path default-path export-enabled? false}}]
   (let [path (route-path path)
         {:keys [title selection controls chart table empty-message]} screen]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>oscope · " (esc title) "</title><style>" style "</style></head><body>"
         "<header><h1>oscope</h1><p>Explore bounded distributions from embedded telemetry.</p></header><main>"
         (render-controls controls selection path) "<h2>" (esc title) "</h2>"
         (if chart
           (str "<div class=\"grid\"><section class=\"panel\">"
                (plotje/spec->svg chart) "</section><section class=\"panel\">"
                (render-table table) "</section></div>")
           (str "<section class=\"panel\" role=\"status\"><p>" (esc empty-message)
                "</p>" (render-table table) "</section>"))
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
  ([source {:keys [path authorize-export?]
            :or {path default-path authorize-export? same-origin-export?}}]
   (let [path (route-path path)
         export-path (export-path path)
         admission (or (:export-admission source)
                       {:capacity 1 :active (atom 0)})]
     (fn [{:keys [request-method uri query-string query-params] :as request}]
       (cond
        (= path uri)
        (if (not= :get request-method)
           {:status 405 :headers (assoc html-headers "Allow" "GET")
            :body "method not allowed"}
           (let [params (or query-params (parse-query-params query-string))
                 selection (selection-from-params params)
                 screen ((:load-command source)
                         [:web (System/nanoTime)] selection)]
             {:status 200 :headers html-headers
              :body (render-page screen {:path path
                                         :export-enabled?
                                         (ifn? (:export-command source))})}))

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

        :else nil)))))

(defn sample-handler []
  (handler {:load-command (fn [_ selection]
                            (sample/screen-for-selection selection))}))

(defn -main [& _] (println (render-page sample/default-screen)))
