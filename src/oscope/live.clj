(ns oscope.live
  "Owned or shared embedded-chDB lifecycle for all live shells."
  (:require [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.query :as query]
            [oscope.query.chdb :as query-chdb]
            [oscope.query.expression.chdb :as query-expression-chdb]
            [oscope.raw-export :as raw-export]
            [oscope.raw-export.chdb :as raw-export-chdb]
            [oscope.typed-catalog :as typed-catalog]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.schema :as schema]))

(defn now-unix-nano [] (* (System/currentTimeMillis) 1000000))

(defn open!
  "Open or share a telemetry connection and ensure the exporter schema.

  With `:connection`, oscope never closes the caller-owned connection. With
  `:db-spec`, oscope owns the new connection and closes it exactly once."
  ([] (open! {}))
  ([{:keys [connection db-spec now-fn export-capacity ensure-schema?
            typed-span-descriptors]
     :or {db-spec "chdb::memory:" now-fn now-unix-nano
          export-capacity 1 ensure-schema? true}}]
   (when-not (and (integer? export-capacity) (pos? export-capacity)
                  (<= export-capacity 16))
     (throw (ex-info "oscope export capacity must be between 1 and 16"
                     {:oscope.live/error true :type ::invalid-export-capacity
                      :export-capacity export-capacity})))
   (when-not (boolean? ensure-schema?)
     (throw (ex-info "oscope ensure-schema? must be boolean"
                     {:oscope.live/error true :type ::invalid-ensure-schema
                      :ensure-schema? ensure-schema?})))
   (let [owned? (nil? connection)
         conn (or connection (jdbc/connection db-spec))]
     (try
       (when ensure-schema? (schema/ensure-schema! conn))
       (let [typed-span-fields (when typed-span-descriptors
                                 (typed-catalog/acquire conn typed-span-descriptors))
             closed? (atom false)
             loader (fn [selection]
                      (when @closed?
                        (throw (ex-info "oscope live source is closed"
                                        {:oscope.live/error true :type ::closed})))
                      (let [plan (query/compile-query selection (now-fn))]
                        (if typed-span-descriptors
                          (view-model/screen
                           plan
                           (query-chdb/run conn plan
                                           {:typed-span-descriptors typed-span-descriptors
                                            :typed-span-fields typed-span-fields})
                           {:typed-span-fields typed-span-fields})
                          (view-model/screen plan (query-chdb/run conn plan)))))
             load-command (fn [request-id selection]
                            (effect/run-command
                             loader (command/query-command request-id selection)))
             plotje-query-active (atom false)
             plotje-query-command
             (fn [_request-id expression]
               (when @closed?
                 (throw (ex-info "oscope live source is closed"
                                 {:oscope.live/error true :type ::closed})))
               (when-not (compare-and-set! plotje-query-active false true)
                 (throw (ex-info "oscope Plotje query capacity reached"
                                 {:oscope.live/error true
                                  :type ::plotje-query-capacity :status 503})))
               (try
                 (query-expression-chdb/execute! conn expression (now-fn))
                 (finally (reset! plotje-query-active false))))
             exporter (fn [selection]
                        (when @closed?
                          (throw (ex-info "oscope live source is closed"
                                          {:oscope.live/error true :type ::closed})))
                        (raw-export-chdb/execute! conn selection))
             export-command (fn [request-id selection]
                              (effect/run-export-command
                               exporter
                               (command/export-command request-id selection)))]
         (cond->
          {:connection conn :db-spec db-spec :owned? owned?
           :export-admission {:capacity export-capacity :active (atom 0)}
           :loader loader
           :screen (load-command :initial query/default-selection)
           :load-command load-command
           :plotje-query-command plotje-query-command
           :export-command export-command
           :closed? closed?
           :close! (fn []
                     (when (compare-and-set! closed? false true)
                       (when owned? (.close conn))))}
          typed-span-descriptors
          (assoc :typed-span-descriptors typed-span-descriptors
                 :typed-span-fields typed-span-fields)))
       (catch Throwable error
         (when owned? (.close conn))
         (throw error))))))

(defn close! [source]
  (when-let [close-fn (:close! source)] (close-fn)))
