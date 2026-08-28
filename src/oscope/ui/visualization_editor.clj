(ns oscope.ui.visualization-editor
  "Mountable server-rendered editor for oscope visualization documents."
  (:require [clojure.string :as str]
            [jolt.http.body :as http-body]
            [oscope.hiccup.spec :as hiccup]
            [oscope.plotje.svg :as plotje-svg]
            [oscope.ui.web :as web]
            [oscope.visualization.document :as document])
  (:import [java.net URLDecoder]))

(def default-path "/oscope/edit")
(def max-body-bytes 40000)

(def html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   (str "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; "
        "connect-src 'self'; form-action 'self'; base-uri 'none'")
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"})

(defn- esc [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- route-path [value]
  (when-not (and (string? value)
                 (<= 1 (count value) 200)
                 (re-matches #"/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)?"
                             value))
    (throw (ex-info "oscope editor path must be an absolute URL path"
                    {:oscope.ui/error true :type ::invalid-path :path value})))
  value)

(defn- child-path [path child]
  (let [path (route-path path)]
    (if (= "/" path) (str "/" child) (str path "/" child))))
(defn plotje-path [path] (child-path path "plotje"))
(defn hiccup-path [path] (child-path path "hiccup"))
(defn preview-path [path kind]
  (str (case kind
         :plotje (plotje-path path)
         :hiccup (hiccup-path path)
         (throw (ex-info "unsupported editor kind" {:kind kind})))
       "/preview"))
(defn asset-path [path] (child-path path "editor.js"))

(defn handled-path? [path candidate]
  (contains? #{(plotje-path path) (hiccup-path path)
               (preview-path path :plotje) (preview-path path :hiccup)
               (asset-path path)}
             candidate))

(defn- concat-chunks [chunks total]
  (let [out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [size (alength chunk)]
          (System/arraycopy chunk 0 out offset size)
          (recur (next remaining) (+ offset size)))
        out))))

(defn- bounded-body-bytes [body]
  (let [bytes
        (cond
          (nil? body) (byte-array 0)
          (string? body) (.getBytes ^String body "UTF-8")
          (bytes? body) body
          (satisfies? http-body/RequestBody body)
          (loop [chunks [] total 0]
            (if-let [chunk (http-body/body-recv body)]
              (let [actual (+ total (alength chunk))]
                (when (> actual max-body-bytes)
                  (throw (ex-info "editor request body is too large"
                                  {:oscope.ui/error true :status 413})))
                (recur (conj chunks chunk) actual))
              (concat-chunks chunks total)))
          :else
          (throw (ex-info "unsupported editor request body"
                          {:oscope.ui/error true :status 400})))]
    (when (> (alength bytes) max-body-bytes)
      (throw (ex-info "editor request body is too large"
                      {:oscope.ui/error true :status 413})))
    bytes))

(defn- form-value [body field]
  (let [encoded (String. (bounded-body-bytes body) "UTF-8")]
    (some
     (fn [pair]
       (let [index (str/index-of pair "=")
             decode #(try (URLDecoder/decode % "UTF-8")
                          (catch Throwable _ ""))]
         (when (and index (= field (decode (subs pair 0 index))))
           (decode (subs pair (inc index))))))
     (str/split encoded #"&"))))

(defn- render-value [{:keys [kind value]}]
  (case kind
    :plotje (plotje-svg/spec->svg value)
    :hiccup (hiccup/spec->html value)))

(defn render-preview [raw-document]
  (let [{:keys [kind status error] :as edit-document}
        (document/validate-document raw-document)
        id (str (name kind) "-preview")]
    (str "<section id=\"" id "\" class=\"preview\" aria-live=\"polite\">"
         (if (= :ready status)
           (render-value edit-document)
           (str "<strong>Spec error</strong><p>" (esc error) "</p>"))
         "</section>")))

(def ^:private style
  (str ":root{color-scheme:dark;--bg:#0d1219;--panel:#182231;--text:#f7f9fc;"
       "--muted:#c4cfdd;--line:#52647c;--accent:#8bd3ff}*{box-sizing:border-box}"
       "body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}"
       "header,main{width:min(1200px,calc(100% - 2rem));margin:auto}header{padding:1.25rem 0 .5rem}"
       "nav{display:flex;gap:1rem;flex-wrap:wrap}a{color:var(--accent)}p{color:var(--muted)}"
       "main{display:grid;grid-template-columns:minmax(18rem,.9fr) minmax(20rem,1.1fr);gap:1rem;padding:1rem 0 2rem}"
       "form,.preview{min-width:0;background:var(--panel);border:1px solid var(--line);border-radius:.7rem;padding:1rem}"
       "textarea{display:block;width:100%;min-height:32rem;resize:vertical;background:#090e15;color:var(--text);border:1px solid #7589a3;border-radius:.45rem;padding:.75rem;font:14px/1.45 ui-monospace,monospace}"
       "button{margin-top:.75rem;background:#d8f1ff;color:#071018;border:0;border-radius:.4rem;padding:.6rem 1rem;font-weight:800}"
       "svg{display:block;max-width:100%;height:auto;background:white;border-radius:.35rem}"
       "@media(max-width:760px){main{grid-template-columns:1fr}textarea{min-height:20rem}}"))

(defn render-page
  [raw-document {:keys [path viewer-path]
                 :or {path default-path viewer-path web/default-path}}]
  (let [{:keys [kind text] :as edit-document}
        (document/validate-document raw-document)
        kind-name (name kind)
        title (if (= :plotje kind) "Plotje editor" "Safe Hiccup editor")
        action (if (= :plotje kind) (plotje-path path) (hiccup-path path))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>oscope · " title "</title><style>" style "</style></head><body>"
         "<header><nav aria-label=\"Utilities\"><a href=\"" (esc viewer-path)
         "\">Telemetry</a><a href=\"" (esc (plotje-path path))
         "\">Plotje editor</a><a href=\"" (esc (hiccup-path path))
         "\">Hiccup editor</a></nav><h1>" title "</h1><p>"
         (if (= :plotje kind)
           "Edit a bounded grammar-of-graphics EDN value."
           "Edit bounded, data-only presentation markup. Active content and URLs are rejected.")
         " Rendering works without JavaScript.</p></header><main>"
         "<form method=\"post\" action=\"" (esc action)
         "\" data-oscope-editor data-preview-path=\""
         (esc (preview-path path kind)) "\"><label for=\"oscope-" kind-name
         "-spec\">" (if (= :plotje kind) "Chart specification" "Hiccup value")
         "</label><textarea id=\"oscope-" kind-name
         "-spec\" name=\"spec\" maxlength=\""
         (document/max-text-chars kind) "\">" (esc text)
         "</textarea><button type=\"submit\">Render</button></form>"
         (render-preview edit-document) "</main><script defer src=\""
         (esc (asset-path path)) "\"></script></body></html>")))

(def ^:private progressive-script
  (str "(()=>{'use strict';const f=document.querySelector('[data-oscope-editor]'),i=f?.querySelector('textarea');"
       "let t,c;if(!i)return;i.addEventListener('input',()=>{clearTimeout(t);t=setTimeout(async()=>{"
       "c?.abort();c=new AbortController();try{const r=await fetch(f.dataset.previewPath,{method:'POST',"
       "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({spec:i.value}),signal:c.signal});"
       "if(!r.ok)throw new Error('preview failed');const d=new DOMParser().parseFromString(await r.text(),'text/html'),"
       "n=d.querySelector('.preview');if(n)document.querySelector('.preview')?.replaceWith(n)}catch(e){"
       "if(e.name!=='AbortError')console.error(e)}},300)})})()"))

(defn- seed-plotje [source request]
  (let [screen
        (if-let [load-command (:load-command source)]
          (let [params (or (:query-params request)
                           (web/parse-query-params (:query-string request)))
                selection (web/selection-from-params params)]
            (load-command [:visualization-editor (System/nanoTime)] selection))
          (:screen source))]
    (document/plotje-from-screen screen)))

(defn- dispatch [source path viewer-path request]
  (let [{:keys [request-method uri body]} request
        plotje-uri (plotje-path path)
        hiccup-uri (hiccup-path path)]
    (cond
      (and (= :get request-method) (= uri (asset-path path)))
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=UTF-8"
                 "Cache-Control" "no-store"
                 "Referrer-Policy" "no-referrer"
                 "X-Content-Type-Options" "nosniff"}
       :body progressive-script}

      (and (= :get request-method) (= uri plotje-uri))
      {:status 200 :headers html-headers
       :body (render-page (seed-plotje source request)
                          {:path path :viewer-path viewer-path})}

      (and (= :get request-method) (= uri hiccup-uri))
      {:status 200 :headers html-headers
       :body (render-page (document/default-hiccup)
                          {:path path :viewer-path viewer-path})}

      (and (= :post request-method)
           (or (= uri plotje-uri) (= uri hiccup-uri)))
      (let [kind (if (= uri plotje-uri) :plotje :hiccup)
            edit-document (document/prepare kind (or (form-value body "spec") ""))]
        {:status 200 :headers html-headers
         :body (render-page edit-document {:path path :viewer-path viewer-path})})

      (and (= :post request-method)
           (or (= uri (preview-path path :plotje))
               (= uri (preview-path path :hiccup))))
      (let [kind (if (= uri (preview-path path :plotje)) :plotje :hiccup)]
        {:status 200 :headers html-headers
         :body (render-preview
                (document/prepare kind (or (form-value body "spec") "")))})

      :else
      {:status 405 :headers (assoc html-headers "Allow"
                                   (if (= uri (asset-path path)) "GET" "GET, POST"))
       :body "method not allowed"})))

(defn handler
  "Return a mountable Ring editor handler.

  `run-suppressed` receives a zero-argument thunk. Instrumented hosts should
  bind their generic instrumentation-suppression context there. The standalone
  oscope server has no SDK or telemetry middleware, so its identity default is
  feedback-free by construction."
  ([source] (handler source {}))
  ([source {:keys [path viewer-path run-suppressed]
            :or {path default-path viewer-path web/default-path
                 run-suppressed (fn [thunk] (thunk))}}]
   (let [path (route-path path)
         viewer-path (route-path viewer-path)]
     (when-not (ifn? run-suppressed)
       (throw (ex-info "oscope editor run-suppressed must be callable"
                       {:oscope.ui/error true :type ::invalid-suppression})))
     (fn [request]
       (when (handled-path? path (:uri request))
         (run-suppressed
          (fn []
            (try
              (dispatch source path viewer-path request)
              (catch clojure.lang.ExceptionInfo error
                (let [status (or (:status (ex-data error)) 400)]
                  {:status status
                   :headers (cond-> html-headers
                              (= 413 status) (assoc "Connection" "close"))
                   :body (if (= 413 status)
                           "editor request body is too large"
                           "invalid editor request")}))))))))))
