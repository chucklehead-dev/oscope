(ns oscope.typed-catalog
  "Acquire logical typed-query bindings from exporter-confirmed capabilities."
  (:require [oscope.typed-query :as typed-query]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.explorer :as explorer]))

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
         (keep (fn [{:keys [id key type identity]}]
                 (when (contains? #{:boolean :string} type)
                   {:field-id id :attribute-key key :attribute-type type
                    :manifest-version (:version identity)})))
         (sort-by (juxt :attribute-key :attribute-type :field-id))
         vec)
    (catch Throwable error
      (if (:oscope.typed-catalog/error (ex-data error))
        (throw error)
        (fail! ::catalog-unavailable
               "typed span catalog is unavailable")))))
