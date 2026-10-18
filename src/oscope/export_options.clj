(ns oscope.export-options
  "Pure, closed encoding choices; never storage or lifecycle authority."
  (:require [malli.core :as m]))

(def ^:private schema
  (m/schema
    [:map {:closed true}
     [:insert-format {:optional true} [:enum :json-each-row :json-compact-each-row]]
     [:json-backend {:optional true}
      [:enum :configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
     [:datetime64-wire {:optional true} [:enum :auto :iso-utc :raw-ticks]]
     [:max-insert-bytes {:optional true} [:maybe [:int {:min 1 :max 67108864}]]]
     [:owned-statement-output? {:optional true} :boolean]]))

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
  options)
