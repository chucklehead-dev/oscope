(ns oscope.ui.web
  "Zero-JavaScript HTML renderer and embeddable Ring adapter."
  (:require [clojure.string :as str]
            [oscope.command :as command]
            [oscope.plotje.svg :as plotje]
            [oscope.query :as query]
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
                 (re-matches #"/(?:[A-Za-z0-9._~-]+/)*[A-Za-z0-9._~-]*" value))
    (throw (ex-info "oscope web path must be an absolute URL path"
                    {:oscope.ui/error true :type ::invalid-path :path value})))
  value)
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
       ".grid{display:grid;grid-template-columns:minmax(20rem,1.4fr) minmax(18rem,.6fr);gap:1rem}"
       "svg{display:block;width:100%;height:auto;background:white;border-radius:.35rem}.table-wrap{overflow:auto}"
       "table{width:100%;border-collapse:collapse}caption{text-align:left;font-weight:800;padding:0 0 .6rem}"
       "th,td{text-align:left;border-bottom:1px solid var(--line);padding:.55rem;overflow-wrap:anywhere}td{text-align:right}"
       "@media(max-width:850px){.controls,.grid{grid-template-columns:1fr}}"))

(defn render-page
  ([screen] (render-page screen {}))
  ([screen {:keys [path] :or {path default-path}}]
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
         "</main></body></html>"))))
(def render-snapshot render-page)
(def render render-page)

(defn handler
  "Return a Ring handler backed by one open oscope source.

  It returns nil outside its configured path, never owns/closes the source,
  and routes every GET through the versioned command/effect seam."
  ([source] (handler source {}))
  ([source {:keys [path] :or {path default-path}}]
   (let [path (route-path path)]
     (fn [{:keys [request-method uri query-string query-params]}]
       (when (= path uri)
         (if (not= :get request-method)
           {:status 405 :headers (assoc html-headers "Allow" "GET")
            :body "method not allowed"}
           (let [params (or query-params (parse-query-params query-string))
                 selection (selection-from-params params)
                 screen ((:load-command source)
                         [:web (System/nanoTime)] selection)]
             {:status 200 :headers html-headers
              :body (render-page screen {:path path})})))))))

(defn sample-handler []
  (handler {:load-command (fn [_ selection]
                            (sample/screen-for-selection selection))}))

(defn -main [& _] (println (render-page sample/default-screen)))
