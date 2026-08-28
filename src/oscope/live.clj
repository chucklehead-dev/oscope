(ns oscope.live
  "Owned or shared embedded-chDB lifecycle for all live shells."
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.query :as query]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.schema :as schema]))

(defn now-unix-nano [] (* (System/currentTimeMillis) 1000000))

(defn open!
  "Open or share a telemetry connection and ensure the exporter schema.

  With `:connection`, oscope never closes the caller-owned connection. With
  `:db-spec`, oscope owns the new connection and closes it exactly once."
  ([] (open! {}))
  ([{:keys [connection db-spec now-fn]
     :or {db-spec "chdb::memory:" now-fn now-unix-nano}}]
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))]
     (try
       (schema/ensure-schema! conn)
       (let [closed? (atom false)
             loader (fn [selection]
                      (when @closed?
                        (throw (ex-info "oscope live source is closed"
                                        {:oscope.live/error true :type ::closed})))
                      (let [plan (query/compile-query selection (now-fn))]
                        (view-model/query->screen conn plan)))
             load-command (fn [request-id selection]
                            (effect/run-command
                             loader (command/query-command request-id selection)))]
         {:connection conn :db-spec db-spec :owned? owned?
          :loader loader
          :screen (load-command :initial query/default-selection)
          :load-command load-command
          :closed? closed?
          :close! (fn []
                    (when (compare-and-set! closed? false true)
                      (when owned? (.close conn))))})
       (catch Throwable error
         (when owned? (.close conn))
         (throw error))))))

(defn close! [source]
  (when-let [close-fn (:close! source)] (close-fn)))
