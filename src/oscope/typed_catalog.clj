(ns oscope.typed-catalog
  "Acquire logical typed-query bindings from exporter-confirmed capabilities."
  (:require [oscope.typed-query :as typed-query]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.typed-log-explorer :as log-explorer]))

(defn- fail! [type message]
  (throw (ex-info message {:oscope.typed-catalog/error true :type type})))

(defn acquire
  "Return only logical, non-secret field bindings from a confirmed capability."
  [connection descriptor-set]
  (try
    (when-not (= typed-query/filter-capability
                 (explorer/supported-typed-span-filters))
      (fail! ::incompatible-exporter
             "typed span filter capability does not match this oscope build"))
    (->> (projection/confirmed-span-fields descriptor-set connection)
         (keep (fn [{:keys [id key type location identity]}]
                 (when (contains? (set (:types typed-query/filter-capability)) type)
                   {:field-id id :attribute-key key :attribute-type type
                    :attribute-location location
                    :manifest-version (:version identity)})))
         (sort-by (juxt :attribute-key :attribute-location
                        :attribute-type :field-id))
         vec)
    (catch Throwable error
      (if (:oscope.typed-catalog/error (ex-data error))
        (throw error)
        (fail! ::catalog-unavailable
               "typed span catalog is unavailable")))))

(defn acquire-logs
  "Return logical bindings for installer-confirmed typed log attributes."
  [connection descriptor-set]
  (try
    (when-not (= typed-query/log-filter-capability
                 (log-explorer/supported-typed-log-filters))
      (fail! ::incompatible-exporter
             "typed log filter capability does not match this oscope build"))
    (->> (projection/confirmed-log-fields descriptor-set connection)
         (keep (fn [{:keys [id key type location identity]}]
                 (when (and (= :log-attributes location)
                            (contains? (set (:types typed-query/log-filter-capability))
                                       type))
                   {:field-id id :attribute-key key :attribute-type type
                    :attribute-location location
                    :manifest-version (:version identity)})))
         (sort-by (juxt :attribute-key :attribute-type :field-id))
         vec)
    (catch Throwable error
      (if (:oscope.typed-catalog/error (ex-data error))
        (throw error)
        (fail! ::catalog-unavailable
               "typed log catalog is unavailable")))))
