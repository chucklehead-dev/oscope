(ns oscope.http-app
  "Loopback viewer/receiver route composition without storage ownership."
  (:require [clojure.string :as str]
            [oscope.ui.events :as events]
            [oscope.ui.visualization-editor :as visualization-editor]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]))

(def default-host "127.0.0.1")
(def default-port 4318)
(def default-http-workers 2)
(def default-http-queue-capacity 8)

(def ^:private text-headers
  {"Content-Type" "text/plain; charset=UTF-8"
   "Cache-Control" "no-store"
   "X-Content-Type-Options" "nosniff"})

(defn- request-header [request header-name]
  (let [target (str/lower-case header-name)]
    (some (fn [[key value]]
            (when (= target
                     (str/lower-case
                      (if (keyword? key) (name key) (str key))))
              (str/trim (str value))))
          (:headers request))))

(defn- expected-authority [authority]
  (if (fn? authority) (authority) authority))

(defn- authority-response []
  {:status 421
   :headers (assoc text-headers "Connection" "close")
   :body "misdirected request\n"})

(defn handler
  "Compose optional receiver and viewer handlers without owning storage."
  [{:keys [otlp-handler otlp-paths oscope-handler workbench-handler
           events-handler visualization-editor-handler authority]
    :or {otlp-paths #{}
         authority (str default-host ":" default-port)}}]
  (fn [{:keys [request-method uri] :as request}]
    (let [expected (expected-authority authority)]
      (cond
        ;; A loopback bind alone does not prevent browser DNS rebinding.
        (or (nil? expected)
            (not= expected (request-header request "host")))
        (authority-response)

        (and otlp-handler (contains? otlp-paths uri))
        (otlp-handler request)
        (and workbench-handler
             (workbench/handled-path? workbench/default-path uri))
        (workbench-handler request)
        (and events-handler
             (events/handled-path? events/default-path uri))
        (events-handler request)
        (and visualization-editor-handler
             (visualization-editor/handled-path?
              visualization-editor/default-path uri))
        (visualization-editor-handler request)
        (web/handled-path? web/default-path uri)
        (oscope-handler request)
        (and (= :get request-method) (= "/healthz" uri))
        {:status 200 :headers text-headers :body "ok\n"}
        (and (= :get request-method) (= "/" uri))
        {:status 303
         :headers {"Location" (if workbench-handler
                                workbench/default-path
                                web/default-path)
                   "Cache-Control" "no-store"}
         :body ""}
        :else
        {:status 404 :headers text-headers :body "not found"}))))
