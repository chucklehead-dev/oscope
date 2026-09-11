(ns oscope.query.chdb
  "Embedded chDB execution adapter for pure oscope query plans."
  (:require [oscope.query :as query]
            [oscope.typed-query :as typed-query]
            [otel.exporter.chdb.explorer :as explorer]))

(defn run
  ([connection plan] (run connection plan {}))
  ([connection plan {:keys [typed-span-descriptors typed-span-fields]}]
  (let [{:keys [selection request]} (query/validate-plan plan)]
    (case (:mode selection)
      :typed-span-filter
      (let [binding (typed-query/resolve-binding typed-span-fields
                                                  (:schema-binding request))]
        (when-not (= typed-query/filter-capability
                     (explorer/supported-typed-span-filters))
          (throw (ex-info "oscope typed span filter choices do not match the chDB explorer"
                          {:oscope.query/error true
                           :type ::incompatible-typed-span-filter})))
        (explorer/typed-span-filtered-traces
         connection typed-span-descriptors
         (-> request (dissoc :schema-binding)
             (assoc :signal :spans :attribute-key (:attribute-key binding)))))

      :cumulative-histogram-series
      (do
        (when-not (= query/cumulative-histogram-series-options
                     (explorer/supported-cumulative-histogram-series))
          (throw
           (ex-info
            "oscope cumulative histogram choices do not match the chDB explorer"
            {:oscope.query/error true
             :type ::incompatible-cumulative-histogram-series
             :oscope-options query/cumulative-histogram-series-options
             :explorer-options
             (explorer/supported-cumulative-histogram-series)})))
        (explorer/cumulative-histogram-series connection request))

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
        (explorer/top-values connection request))))))
