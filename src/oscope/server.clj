(ns oscope.server
  "Standalone loopback OTLP/HTTP receiver and oscope viewer composition."
  (:require [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.ui.visualization-editor :as visualization-editor]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.otlp.http-receiver :as receiver]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(def default-host "127.0.0.1")
(def default-port 4318)
(def default-db-spec "chdb:./oscope-data")

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
  "Compose receiver and viewer handlers without adding instrumentation.

  Absence of an OTel SDK, tracer, logger, or middleware in this namespace makes
  collector feedback impossible by construction."
  [{:keys [otlp-handler oscope-handler visualization-editor-handler authority]
    :or {authority (str default-host ":" default-port)}}]
  (fn [{:keys [request-method uri] :as request}]
    (let [expected (expected-authority authority)]
      (cond
      ;; A loopback bind does not stop a hostile public hostname from resolving
      ;; to 127.0.0.1. Require the exact numeric authority before any receiver,
      ;; viewer, export, or health work so browser DNS rebinding cannot cross
      ;; the standalone process boundary. A nil authority is the fail-closed
      ;; startup state while an ephemeral listener's actual port is published.
      (or (nil? expected)
          (not= expected (request-header request "host")))
      (authority-response)

      (contains? receiver/receiver-paths uri) (otlp-handler request)
      (and visualization-editor-handler
           (visualization-editor/handled-path?
            visualization-editor/default-path uri))
      (visualization-editor-handler request)
      (web/handled-path? web/default-path uri)
      (oscope-handler request)
      (and (= :get request-method) (= "/healthz" uri))
      {:status 200 :headers text-headers :body "ok\n"}
      (and (= :get request-method) (= "/" uri))
      {:status 303 :headers {"Location" web/default-path
                             "Cache-Control" "no-store"}
       :body ""}
      :else {:status 404 :headers text-headers :body "not found"}))))

(defn- close-face! [face exporter]
  (let [closed? (case face
                  :spans (export/shutdown-exporter! exporter)
                  :logs (logs/shutdown-log-exporter! exporter)
                  :metrics (export/shutdown-metric-exporter! exporter))]
    (when-not closed?
      (throw (ex-info "oscope exporter face did not close"
                      {:oscope.server/error true :face face})))
    true))

(defn- stop-result [state error]
  (cond-> {:status (if (= :closed (:phase state)) :closed :closing)
           :phase (:phase state)}
    error (assoc :errors [error])))

(defn- stop-lifecycle! [{:keys [server source exporter connection state lock]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        (when-not (:ingress-stopped? @state)
          (http/stop-server server)
          (swap! state assoc :ingress-stopped? true :phase :retiring-oscope))
        (when-not (:oscope-retired? @state)
          (live/close! source)
          (swap! state assoc :oscope-retired? true :phase :closing-exporters))
        (doseq [face [:spans :logs :metrics]]
          (when-not (contains? (:closed-faces @state) face)
            (close-face! face exporter)
            (swap! state update :closed-faces conj face)))
        (swap! state assoc :phase :closing-connection)
        (when-not (:connection-closed? @state)
          (.close connection)
          (swap! state assoc :connection-closed? true :phase :closed))
        (stop-result @state nil)
        (catch Throwable error
          (stop-result @state error))))))

(defn start!
  "Start one loopback server backed by one shared embedded chDB connection.

  `:port` may be zero for an ephemeral test port. jolt-http currently binds
  loopback at the transport layer, so `:host` deliberately accepts only
  127.0.0.1. The returned `:stop!` is idempotent and retries the first
  incomplete ownership boundary on each call."
  ([] (start! {}))
  ([{:keys [host port db-spec]
     :or {host default-host port default-port db-spec default-db-spec}}]
   (when-not (= default-host host)
     (throw (ex-info "oscope standalone receiver must bind to 127.0.0.1"
                     {:oscope.server/error true :host host})))
   (when-not (and (integer? port) (<= 0 port 65535))
     (throw (ex-info "oscope port must be between 0 and 65535"
                     {:oscope.server/error true :port port})))
   (let [conn (jdbc/connection db-spec)
         exporter* (atom nil)
         source* (atom nil)
         server* (atom nil)
         authority* (atom (when (pos? port) (str host ":" port)))]
     (try
       ;; This is the sole schema owner. The shared oscope source below skips
       ;; its otherwise-default schema check.
       (let [exporter (chdb-export/exporter
                       {:connection conn :signals #{:spans :logs :metrics}})
             _ (reset! exporter* exporter)
             source (live/open! {:connection conn :ensure-schema? false})
             _ (reset! source* source)
             editor-handler (visualization-editor/handler source)
             app-handler (handler {:otlp-handler (otlp/handler exporter)
                                   :oscope-handler
                                   (web/handler
                                    source
                                    {:visualization-editor-path
                                     (visualization-editor/plotje-path
                                      visualization-editor/default-path)})
                                   :visualization-editor-handler editor-handler
                                   :authority #(deref authority*)})
             server (http/run-server app-handler :port port :server-name host
                                     :reuse-address? true :pool-size 2)
             _ (reset! server* server)
             _ (reset! authority* (str host ":" (:port server)))
             lifecycle {:host host :port (:port server) :db-spec db-spec
                        :connection conn :exporter exporter :source source
                        :server server
                        :state (atom {:phase :open :ingress-stopped? false
                                      :oscope-retired? false :closed-faces #{}
                                      :connection-closed? false})
                        :lock (Object.)}]
         (assoc lifecycle :stop! #(stop-lifecycle! lifecycle)))
       (catch Throwable error
         (when-let [server @server*]
           (try (http/stop-server server) (catch Throwable _ nil)))
         (when-let [source @source*]
           (try (live/close! source) (catch Throwable _ nil)))
         (when-let [exporter @exporter*]
           (doseq [face [:spans :logs :metrics]]
             (try (close-face! face exporter) (catch Throwable _ nil))))
         (try (.close conn) (catch Throwable _ nil))
         (throw error))))))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    {:status :closed :phase :closed}))

(defn env-options []
  (let [raw-port (System/getenv "OSCOPE_PORT")
        port (if (str/blank? raw-port) default-port (parse-long raw-port))]
    {:host (or (not-empty (System/getenv "OSCOPE_HOST")) default-host)
     :port port
     :db-spec (or (not-empty (System/getenv "OSCOPE_CHDB_SPEC"))
                  default-db-spec)}))
