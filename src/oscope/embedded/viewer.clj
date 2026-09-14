(ns oscope.embedded.viewer
  "Viewer-only HTTP lifecycle borrowing an existing embedded Oscope owner."
  (:require [jolt.http.server :as http]
            [oscope.error :as error]
            [oscope.http-app :as http-app]
            [oscope.http-executor :as http-executor]
            [oscope.ui.events :as events]
            [oscope.ui.visualization-editor :as visualization-editor]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]))

(defn- invalid! [message type]
  (throw (ex-info message {:oscope.embedded.viewer/error true :type type})))

(defn- stop-result [state failure]
  (cond-> {:status (if (= :closed (:phase state)) :closed :closing)
           :phase (:phase state)}
    failure (assoc :errors [failure])))

(defn- stop-lifecycle!
  [{:keys [server http-executor state lock]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        (when-not (:listener-stopped? @state)
          (http/stop-server server)
          (swap! state assoc :listener-stopped? true
                 :phase :stopping-query-workers
                 :operation :stop-query-workers))
        (when-not (:query-workers-stopped? @state)
          (http-executor/stop! http-executor)
          (swap! state assoc :query-workers-stopped? true :phase :closed))
        (stop-result @state nil)
        (catch Throwable cause
          (let [current @state]
            (stop-result current
                         (error/lifecycle-failure (:operation current)
                                                  cause))))))))

(defn start!
  "Serve the normal oscope viewer over an existing embedded lifecycle.

  The viewer borrows `:source` and `:connection`; it never creates or closes an
  OTel SDK, exporter, Durable writer, connection, or source. It owns only its
  loopback listener and bounded query-request workers. Stop the viewer before
  stopping the embedded owner so no query can race source retirement.

  `:port` defaults to zero and the returned `:url` contains the actual bound
  port. Only the numeric loopback host is accepted."
  ([owner] (start! owner {}))
  ([{:keys [source connection]} {:keys [host port http-workers
                                        http-queue-capacity]
                                 :or {host http-app/default-host
                                      port 0
                                      http-workers http-app/default-http-workers
                                      http-queue-capacity
                                      http-app/default-http-queue-capacity}}]
   (when-not (and source connection)
     (invalid! "oscope viewer requires an existing source and connection"
               ::invalid-owner))
   (when-not (= http-app/default-host host)
     (invalid! "oscope viewer must bind to 127.0.0.1" ::invalid-host))
   (when-not (and (integer? port) (<= 0 port 65535))
     (invalid! "oscope viewer port must be between 0 and 65535" ::invalid-port))
   (when-not (and (integer? http-workers) (pos? http-workers)
                  (integer? http-queue-capacity)
                  (pos? http-queue-capacity))
     (invalid! "oscope viewer worker limits must be positive" ::invalid-workers))
   (let [owned-http-executor* (atom nil)
         server* (atom nil)
         authority* (atom (when (pos? port) (str host ":" port)))]
     (try
       (let [owned-http-executor
             (http-executor/start! {:workers http-workers
                                    :queue-capacity http-queue-capacity})
             _ (reset! owned-http-executor* owned-http-executor)
             app-handler
             (http-app/handler
              {:workbench-handler (workbench/handler connection)
               :events-handler (events/handler connection)
               :oscope-handler
               (web/handler
                source
                {:visualization-editor-path
                 (visualization-editor/plotje-path
                  visualization-editor/default-path)})
               :visualization-editor-handler
               (visualization-editor/handler source)
               :authority #(deref authority*)})
             listener (http/run-server
                       app-handler :port port :server-name host
                       :reuse-address? true
                       :executor (:executor owned-http-executor))
             _ (reset! server* listener)
             actual-port (:port listener)
             _ (when-not (and (integer? actual-port)
                              (<= 1 actual-port 65535))
                 (invalid! "oscope viewer listener returned an invalid port"
                           ::invalid-bound-port))
             _ (reset! authority* (str host ":" actual-port))
             internal {:server listener
                       :http-executor owned-http-executor
                       :state (atom {:phase :open
                                     :operation :stop-listener
                                     :listener-stopped? false
                                     :query-workers-stopped? false})
                       :lock (Object.)}]
         {:host host
          :port actual-port
          :url (str "http://" host ":" actual-port workbench/default-path)
          :stop! #(stop-lifecycle! internal)})
       (catch Throwable cause
         (when-let [listener @server*]
           (try (http/stop-server listener) (catch Throwable _ nil)))
         (when-let [owned-http-executor @owned-http-executor*]
           (try (http-executor/stop! owned-http-executor)
                (catch Throwable _ nil)))
         (throw cause))))))

(defn stop! [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    (invalid! "invalid oscope viewer lifecycle" ::invalid-lifecycle)))
