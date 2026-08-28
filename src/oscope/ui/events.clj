(ns oscope.ui.events
  "Static-first raw log and metric event explorer."
  (:require [clojure.string :as str]
            [oscope.telemetry :as telemetry]
            [oscope.ui.web :as aggregate]
            [oscope.ui.workbench :as workbench])
  (:import [java.net URLDecoder]))

(def default-path "/oscope/events")
(def ^:private trace-id-pattern #"[0-9a-f]{32}")
(def ^:private html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'"
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"})
(def ^:private text-headers
  {"Content-Type" "text/plain; charset=UTF-8" "Cache-Control" "no-store"
   "X-Content-Type-Options" "nosniff"})

(defn- esc [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- mounted [base suffix]
  (str (str/replace base #"/+$" "") suffix))

(defn handled-path? [base candidate]
  (contains? #{base (mounted base "/refresh") (mounted base "/live.js")}
             candidate))

(defn- parse-params [query-string]
  (if (> (count (or query-string "")) 4096)
    {}
    (try
      (reduce
       (fn [result pair]
         (let [index (str/index-of pair "=")
               key (URLDecoder/decode (if index (subs pair 0 index) pair) "UTF-8")
               value (URLDecoder/decode (if index (subs pair (inc index)) "") "UTF-8")]
           (if (or (contains? result key) (> (count value) 200))
             result (assoc result key value))))
       {} (remove str/blank? (str/split (or query-string "") #"&")))
      (catch Throwable _ {}))))

(defn selection-from-query [query-string]
  (let [params (parse-params query-string)
        raw-limit (get params "limit")
        limit (when (and (string? raw-limit) (re-matches #"[0-9]{1,3}" raw-limit))
                (parse-long raw-limit))]
    (telemetry/normalize-event-selection
     {:signal (if (= "metrics" (get params "signal")) :metrics :logs)
      :service (get params "service")
      :search (get params "search")
      :severity (get params "severity")
      :metric-kind (get params "metric-kind")
      :window (get params "window")
      :limit (if (and limit (<= 1 limit 100)) limit 50)})))

(defn- option [value label selected]
  (str "<option value=\"" (esc value) "\""
       (when (= value selected) " selected") ">" (esc label) "</option>"))

(defn- controls [path selection]
  (let [{:keys [signal service search severity metric-kind window limit]} selection]
    (str "<form class=\"event-controls\" method=\"get\" action=\"" path
         "\" aria-label=\"Event query\"><label>Signal<select name=\"signal\">"
         (option "logs" "Logs" (name signal))
         (option "metrics" "Metrics" (name signal)) "</select></label>"
         "<label>Service<input name=\"service\" maxlength=\"100\" value=\""
         (esc service) "\" placeholder=\"All services\"></label>"
         "<label>" (if (= signal :logs) "Body contains" "Metric name contains")
         "<input type=\"search\" name=\"search\" maxlength=\"200\" value=\""
         (esc search) "\"></label>"
         (if (= signal :logs)
           (str "<label>Severity<select name=\"severity\">"
                (option "" "All severities" severity)
                (apply str (map #(option % % severity)
                                ["TRACE" "DEBUG" "INFO" "WARN" "ERROR" "FATAL"]))
                "</select></label>")
           (str "<label>Metric kind<select name=\"metric-kind\">"
                (option "" "All metric kinds" metric-kind)
                (option "gauge" "Gauge" metric-kind)
                (option "sum" "Sum" metric-kind)
                (option "histogram" "Histogram" metric-kind)
                "</select></label>"))
         "<label>Window<select name=\"window\">"
         (apply str (map (fn [[value label]] (option value label window))
                         [["15m" "Last 15 minutes"] ["1h" "Last hour"]
                          ["6h" "Last 6 hours"] ["24h" "Last 24 hours"]]))
         "</select></label><label>Maximum rows<input type=\"number\" name=\"limit\" min=\"1\" max=\"100\" value=\""
         limit "\"></label><button type=\"submit\">Run query</button></form>")))

(defn- trace-link [trace-id]
  (if (and (string? trace-id) (re-matches trace-id-pattern trace-id))
    (str "<a href=\"" workbench/default-path "/traces/" trace-id
         "\"><code>" trace-id "</code></a>")
    "<span class=\"muted\">unscoped</span>"))

(defn- logs-table [rows]
  (str "<table><caption>Recent log records</caption><thead><tr>"
       "<th>Time</th><th>Severity</th><th>Service</th><th>Body</th><th>Trace</th>"
       "</tr></thead><tbody>"
       (apply str
              (map (fn [{:keys [timestamp severity service body traceId]}]
                     (str "<tr><td><time>" (esc timestamp) "</time></td><td>"
                          (esc severity) "</td><td>" (esc service)
                          "</td><td class=\"event-body\">" (esc body)
                          "</td><td>" (trace-link traceId) "</td></tr>"))
                   rows))
       "</tbody></table>"))

(defn- metrics-table [rows]
  (str "<table><caption>Recent metric points</caption><thead><tr>"
       "<th>Time</th><th>Kind</th><th>Service</th><th>Name</th><th>Value</th><th>Unit</th>"
       "</tr></thead><tbody>"
       (apply str
              (map (fn [{:keys [timestamp kind service name value count sum unit]}]
                     (str "<tr><td><time>" (esc timestamp) "</time></td><td>"
                          (esc kind) "</td><td>" (esc service) "</td><td>"
                          (esc name) "</td><td>" (esc (if (= kind "histogram")
                                                       (str "count=" count ", sum=" sum)
                                                       value))
                          "</td><td>" (esc unit) "</td></tr>"))
                   rows))
       "</tbody></table>"))

(defn render-fragment [selection rows]
  (str "<section id=\"oscope-events\" aria-live=\"polite\"><header class=\"screen-head\"><div><h2>"
       (if (= :logs (:signal selection)) "Log records" "Metric points")
       "</h2><p>Newest first · bounded to " (:limit selection)
       " rows in the selected " (esc (:window selection)) " window.</p></div>"
       "<span class=\"live-status\">Live refresh every 2 seconds</span></header>"
       (if (seq rows)
         (str "<div class=\"table-wrap\">"
              (if (= :logs (:signal selection))
                (logs-table rows) (metrics-table rows)) "</div>")
         "<p class=\"empty\" role=\"status\">No telemetry matched this query.</p>")
       "</section>"))

(def ^:private style
  (str ":root{color-scheme:dark;--bg:#0d1219;--panel:#182231;--text:#f7f9fc;--muted:#c4cfdd;--line:#52647c;--accent:#8bd3ff}"
       "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}"
       "header,main,.oscope-nav{width:min(1280px,calc(100% - 2rem));margin:auto}header{padding:1.25rem 0 .5rem}.oscope-nav{display:flex;gap:1rem;flex-wrap:wrap;padding-top:1rem}"
       "a{color:var(--accent)}p,.muted{color:var(--muted)}.event-controls,#oscope-events{background:var(--panel);border:1px solid var(--line);border-radius:.7rem;padding:1rem;margin:1rem 0}"
       ".event-controls{display:grid;grid-template-columns:repeat(6,minmax(8rem,1fr)) auto;gap:.7rem;align-items:end}label{font-weight:750}"
       "select,input{display:block;width:100%;margin-top:.25rem;background:#090e15;color:var(--text);border:1px solid #7589a3;border-radius:.4rem;padding:.55rem}"
       "button{background:#d8f1ff;color:#071018;border:0;border-radius:.4rem;padding:.65rem 1rem;font-weight:800}.screen-head{display:flex;justify-content:space-between;gap:1rem;align-items:start}"
       ".live-status{color:var(--muted)}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse}caption{text-align:left;font-weight:800;padding:.6rem 0}"
       "th,td{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:.55rem;overflow-wrap:anywhere}.event-body{min-width:18rem;max-width:40rem}.empty{padding:2rem;text-align:center}"
       "@media(max-width:1000px){.event-controls{grid-template-columns:repeat(2,1fr)}}@media(max-width:600px){.event-controls{grid-template-columns:1fr}.screen-head{display:block}}"))

(def ^:private live-script
  (str "(()=>{'use strict';let timer=null,pending=false;const refresh=async()=>{const root=document.querySelector('#oscope-events');"
       "if(!root||pending||document.hidden)return;pending=true;try{const url=new URL(location.href);"
       "url.pathname=url.pathname.replace(/\\/$/,'')+'/refresh';const response=await fetch(url,{headers:{Accept:'text/html'},cache:'no-store'});"
       "if(!response.ok)return;const parsed=new DOMParser().parseFromString(await response.text(),'text/html');"
       "const next=parsed.querySelector('#oscope-events');if(next)root.replaceWith(next)}finally{pending=false}};"
       "const schedule=()=>{clearInterval(timer);if(!document.hidden)timer=setInterval(refresh,2000)};"
       "document.addEventListener('visibilitychange',schedule);schedule()})()"))

(defn handler
  ([connection] (handler connection {}))
  ([connection {:keys [path now-fn]
                :or {path default-path
                     now-fn #(* (System/currentTimeMillis) 1000000)}}]
   (fn [{:keys [request-method uri query-string]}]
     (when (handled-path? path uri)
       (if (not= :get request-method)
         {:status 405 :headers (assoc text-headers "Allow" "GET")
          :body "method not allowed"}
         (let [selection (selection-from-query query-string)]
           (cond
             (= uri (mounted path "/live.js"))
             {:status 200
              :headers {"Content-Type" "text/javascript; charset=UTF-8"
                        "Cache-Control" "no-store"
                        "X-Content-Type-Options" "nosniff"}
              :body live-script}

             (= uri (mounted path "/refresh"))
             {:status 200 :headers html-headers
              :body (render-fragment
                     selection (telemetry/query-events connection selection (now-fn)))}

             :else
             (let [rows (telemetry/query-events connection selection (now-fn))]
               {:status 200 :headers html-headers
                :body
                (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                     "<title>oscope · events</title><style>" style "</style><script defer src=\""
                     (mounted path "/live.js") "\"></script></head><body>"
                     "<nav class=\"oscope-nav\" aria-label=\"oscope views\"><a href=\""
                     workbench/default-path "\">Traces</a><a href=\"" path
                     "\">Logs &amp; metrics</a><a href=\"" aggregate/default-path
                     "\">Charts &amp; distributions</a></nav><header><h1>Telemetry events</h1>"
                     "<p>Query bounded raw log records and metric points from embedded chDB.</p></header><main>"
                     (controls path selection) (render-fragment selection rows)
                     "</main></body></html>")}))))))))
