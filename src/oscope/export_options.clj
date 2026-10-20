(ns oscope.export-options
  "Closed encoding choices and explicit capability preflight; no storage authority."
  (:require [malli.core :as m]))

(def ^:private schema
  (m/schema
    [:map {:closed true}
     [:insert-format {:optional true} [:enum :json-each-row :json-compact-each-row]]
     [:json-backend {:optional true}
      [:enum :configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
     [:datetime64-wire {:optional true} [:enum :auto :iso-utc :raw-ticks]]
     [:max-insert-bytes {:optional true} [:maybe [:int {:min 1 :max 67108864}]]]
     [:owned-statement-output? {:optional true} :boolean]
     [:experimental-span-codec? {:optional true} :boolean]
     [:experimental-span-payload-cache? {:optional true} :boolean]
     [:experimental-span-flat-map-cache? {:optional true} :boolean]
     [:experimental-span-nested-fragment-cache? {:optional true} :boolean]]))

(defn validate!
  "Return unchanged encoding options, or throw a fixed error without values.
  This is shape/combination validation only, not runtime ABI qualification."
  [options durable?]
  (when-not (m/validate schema options)
    (throw (ex-info "Invalid exporter encoding options"
                    {:oscope.export-options/error true :type ::invalid-options})))
  (when (and (:owned-statement-output? options)
             (not (and durable?
                       (= :json-compact-each-row (:insert-format options))
                       (= :native-guarded-byte-batch (:json-backend options)))))
    (throw (ex-info "Owned output requires native-byte compact Durable storage"
                    {:oscope.export-options/error true :type ::incompatible-owned-output})))
  (when (and (= :raw-ticks (:datetime64-wire options)) (not durable?))
    (throw (ex-info "Raw timestamp ticks require Durable storage"
                    {:oscope.export-options/error true :type ::incompatible-timestamp-wire})))
  (when (and (:experimental-span-codec? options)
             (not (and durable? (:owned-statement-output? options)
                       (= :json-compact-each-row (:insert-format options))
                       (= :native-guarded-byte-batch (:json-backend options)))))
    (throw (ex-info "Experimental span codec requires owned compact Durable output"
                    {:oscope.export-options/error true :type ::incompatible-span-codec})))
  (when (and (some options [:experimental-span-payload-cache?
                           :experimental-span-flat-map-cache?
                           :experimental-span-nested-fragment-cache?])
             (not (:experimental-span-codec? options)))
    (throw (ex-info "Experimental span caches require the explicit span codec"
                    {:oscope.export-options/error true :type ::incompatible-span-cache})))
  options)

(defn preflight!
  "Resolve explicitly requested span-codec capabilities before app acquisition.
  Ordinary options load no optional codec. An older exporter lacking the public
  probe rejects rather than silently ignoring flags. No readiness/DDL proof."
  [options]
  (when (:experimental-span-codec? options)
    (try
      (require 'otel.exporter.chdb)
      (let [check (ns-resolve 'otel.exporter.chdb 'check-experimental-span-codec!)]
        (when-not (and check (ifn? @check))
          (throw (ex-info "Exporter codec probe unavailable" {})))
        (@check (select-keys options [:experimental-span-payload-cache?
                                      :experimental-span-flat-map-cache?
                                      :experimental-span-nested-fragment-cache?])))
      (catch Throwable _
        (throw (ex-info "Experimental span codec requires its optional dependency and compatible stack"
                        {:oscope.export-options/error true :type ::span-codec-unavailable})))))
  options)
