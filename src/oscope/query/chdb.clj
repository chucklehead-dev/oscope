(ns oscope.query.chdb
  "Embedded chDB execution adapter for pure oscope query plans."
  (:require [oscope.query :as query]
            [otel.exporter.chdb.explorer :as explorer]))

(defn run [connection plan]
  (when-not (= (query/supported-fields) (explorer/supported-fields))
    (throw (ex-info "oscope query fields do not match the chDB explorer"
                    {:oscope.query/error true
                     :type ::incompatible-explorer-fields
                     :oscope-fields (query/supported-fields)
                     :explorer-fields (explorer/supported-fields)})))
  (explorer/top-values connection (:request (query/validate-plan plan))))
