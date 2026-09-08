(ns oscope.query.chdb
  "Embedded chDB execution adapter for pure oscope query plans."
  (:require [oscope.query :as query]
            [otel.exporter.chdb.explorer :as explorer]))

(defn run [connection plan]
  (let [{:keys [selection request]} (query/validate-plan plan)]
    (case (:mode selection)
      :counter-series
      (do
        (when-not (= query/counter-series-options
                     (explorer/supported-cumulative-counter-series))
          (throw (ex-info "oscope counter series choices do not match the chDB explorer"
                          {:oscope.query/error true
                           :type ::incompatible-counter-series
                           :oscope-options query/counter-series-options
                           :explorer-options
                           (explorer/supported-cumulative-counter-series)})))
        (explorer/cumulative-counter-series connection request))

      :metric-series
      (do
        (when-not (= query/metric-series-options
                     (explorer/supported-metric-series))
          (throw (ex-info "oscope metric series choices do not match the chDB explorer"
                          {:oscope.query/error true
                           :type ::incompatible-metric-series
                           :oscope-options query/metric-series-options
                           :explorer-options (explorer/supported-metric-series)})))
        (explorer/metric-series connection request))

      (do
        (when-not (= (query/supported-fields) (explorer/supported-fields))
          (throw (ex-info "oscope query fields do not match the chDB explorer"
                          {:oscope.query/error true
                           :type ::incompatible-explorer-fields
                           :oscope-fields (query/supported-fields)
                           :explorer-fields (explorer/supported-fields)})))
        (explorer/top-values connection request)))))
