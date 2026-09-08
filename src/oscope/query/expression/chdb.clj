(ns oscope.query.expression.chdb
  "Embedded chDB execution adapter for pure Plotje telemetry query plans."
  (:require [db.jdbc]
            [jdbc.core :as jdbc]
            [oscope.query.expression :as expression]
            [oscope.query.trace :as query-trace]
            [otel.context :as context]))

(defn execute! [connection query now-unix-nano]
  (query-trace/invoke!
   :oscope.plotje-query/execute
   (fn [_] {:outcome :succeeded})
   (fn [error]
     {:outcome :failed
      :category (if (:oscope.query-expression/error (ex-data error))
                  :expression
                  :backend)})
   (fn []
     (when (nil? connection)
       (throw (ex-info "telemetry query requires a connection"
                       {:oscope.query-expression/error true
                        :type ::invalid-connection})))
     (let [{:keys [sqlvec columns calculations limit]}
           (expression/compile-query query now-unix-nano)]
       (context/with-instrumentation-suppressed
         (mapv (fn [row]
                 (expression/apply-calculations
                  (into {} (map (fn [[internal external]]
                                  [external (get row internal)]) columns))
                  calculations))
               (jdbc/fetch connection sqlvec {:max-rows limit})))))))
