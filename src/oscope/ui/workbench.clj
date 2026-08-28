(ns oscope.ui.workbench
  "Embeddable trace tree and correlated-log workbench for oscope."
  (:require [clojure.string :as str]
            [oscope.telemetry :as telemetry]
            [oscope.ui.web :as aggregate]
            [otel.viewer :as viewer])
  (:import [java.net URLDecoder]))

(def default-path "/oscope/telemetry")
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

(defn- mounted [base suffix]
  (str (str/replace base #"/+$" "") suffix))

(defn handled-path? [base candidate]
  (or (= base candidate)
      (= (mounted base "/refresh") candidate)
      (= (mounted base "/live.js") candidate)
      (= (mounted base "/viewer.js") candidate)
      (boolean (re-matches (re-pattern
                            (str (java.util.regex.Pattern/quote
                                  (mounted base "/traces/")) "[0-9a-f]{32}"))
                           candidate))))

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
  (let [params (parse-params query-string)]
    (telemetry/normalize-selection
     {:service (get params "service")
      :operation (get params "operation")
      :status (get params "status")
      :min-duration-ms (get params "min-duration-ms")
      :window (get params "window")})))

(defn- page [base title fragment live?]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>oscope · " title "</title><style>" (viewer/styles)
       ".oscope-nav{width:min(1120px,calc(100% - 2rem));margin:1rem auto 0;display:flex;gap:1rem;flex-wrap:wrap}"
       ".oscope-nav a{color:#8bd3ff;font-weight:750}</style>"
       (when live? (str "<script defer src=\"" (mounted base "/live.js") "\"></script>"))
       "<script defer src=\"" (mounted base "/viewer.js") "\"></script></head><body>"
       "<nav class=\"oscope-nav\" aria-label=\"oscope views\"><a href=\"" base
       "\">Traces &amp; correlated logs</a><a href=\"" aggregate/default-path
       "\">Logs, metrics &amp; charts</a></nav>" fragment "</body></html>"))

(defn- index-model [connection base selection now]
  {:title "Telemetry workbench"
   :eyebrow "oscope · embedded OpenTelemetry"
   :base-path base
   :enhancement-path "viewer.js"
   :live-attributes {"data-otel-live" "true"}
   :trace-filters (telemetry/query-filter-options connection selection now)
   :summary (telemetry/query-summary connection)
   :traces (telemetry/query-traces connection selection now)
   :logs (telemetry/query-logs connection)})

(def ^:private live-script
  (str "(()=>{'use strict';const root=document.querySelector('#otel-live');if(!root)return;"
       "let timer=null,pending=false;const refresh=async()=>{if(pending||document.hidden)return;pending=true;"
       "try{const url=new URL(location.href);url.pathname=url.pathname.replace(/\\/$/,'')+'/refresh';"
       "const response=await fetch(url,{headers:{Accept:'text/html'},cache:'no-store'});if(!response.ok)return;"
       "const parsed=new DOMParser().parseFromString(await response.text(),'text/html');"
       "const next=parsed.querySelector('#otel-live');if(next)root.replaceChildren(...Array.from(next.childNodes));"
       "}finally{pending=false;}};const schedule=()=>{clearInterval(timer);if(!document.hidden)timer=setInterval(refresh,2000);};"
       "document.addEventListener('visibilitychange',schedule);schedule();})();"))

(defn handler
  ([connection] (handler connection {}))
  ([connection {:keys [path advise-trace now-fn]
                :or {path default-path advise-trace identity
                     now-fn #(* (System/currentTimeMillis) 1000000)}}]
   (fn [{:keys [request-method uri query-string]}]
     (when (handled-path? path uri)
       (if (not= :get request-method)
         {:status 405 :headers (assoc text-headers "Allow" "GET")
          :body "method not allowed"}
         (cond
           (= uri (mounted path "/live.js"))
           {:status 200 :headers {"Content-Type" "text/javascript; charset=UTF-8"
                                  "Cache-Control" "no-store"
                                  "X-Content-Type-Options" "nosniff"}
            :body live-script}

           (= uri (mounted path "/viewer.js"))
           {:status 200 :headers {"Content-Type" "text/javascript; charset=UTF-8"
                                  "Cache-Control" "no-store"
                                  "X-Content-Type-Options" "nosniff"}
            :body (viewer/enhancement-script)}

           (= uri (mounted path "/refresh"))
           (let [selection (selection-from-query query-string)]
             {:status 200 :headers html-headers
              :body (str "<div id=\"otel-live\">"
                         (viewer/render-live-content
                          (index-model connection path selection (now-fn)))
                         "</div>")})

           (= uri path)
           (let [selection (selection-from-query query-string)
                 model (index-model connection path selection (now-fn))]
             {:status 200 :headers html-headers
              :body (page path "Telemetry workbench"
                          (viewer/render-fragment model) true)})

           :else
           (let [trace-id (last (str/split uri #"/"))]
             (if (re-matches trace-id-pattern trace-id)
               {:status 200 :headers html-headers
                :body (page path "Trace detail"
                            (viewer/render-fragment
                             {:title "Trace detail"
                              :eyebrow "oscope · embedded OpenTelemetry"
                              :base-path path
                              :trace (advise-trace
                                      (telemetry/query-trace connection trace-id))})
                            false)}
               {:status 400 :headers text-headers :body "invalid trace id"}))))))))
