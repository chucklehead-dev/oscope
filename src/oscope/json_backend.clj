(ns oscope.json-backend
  "Startup-only selection of the general exporter JSON backend."
  (:require [jdbc.chdb.json-each-row :as encoder]
            [jdbc.chdb.native :as native]
            [otel.exporter.chdb]))

(defn validate! [backend]
  (when-not (#{:configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch} backend)
    (throw (ex-info "Unsupported oscope JSON backend"
                    {:oscope.json-backend/error true :type ::invalid-backend})))
  ;; Probe before connection acquisition, typed DDL, SDK or HTTP workers.
  ;; Explicit unavailable selection must not silently fall back.
  ;; Older exporters do not require this newer scalar API; preserve that graph.
  (when-let [validate-runtime (ns-resolve 'otel.exporter.chdb 'validate-runtime!)]
    (validate-runtime))
  (when (not= :configured backend)
    (encoder/close! (encoder/open-encoder {:parallelism 1 :json-backend backend})))
  backend)

(defn validate-format! [format]
  (when-not (#{:json-each-row :json-compact-each-row} format)
    (throw (ex-info "Unsupported oscope telemetry insert format"
                    {:oscope.json-backend/error true :type ::invalid-format})))
  format)

(defn validate-export-options! [options]
  (when (and (contains? options :owned-statement-output?)
             (not (boolean? (:owned-statement-output? options))))
    (throw (ex-info "Owned statement output must be boolean" {:type ::invalid-owned-output})))
  (when (and (:owned-statement-output? options)
             (not (and (= :native-guarded-byte-batch (:json-backend options))
                       (= :json-compact-each-row (:insert-format options)))))
    (throw (ex-info "Owned output requires native byte compact encoding" {:type ::invalid-owned-output})))
  (when (contains? options :datetime64-wire)
    (when-not (contains? #{:auto :iso-utc :raw-ticks} (:datetime64-wire options))
      (throw (ex-info "Unsupported timestamp wire" {:type ::invalid-timestamp-wire})))
    ;; Explicit new-engine selection fails before typed DDL or acquisition.
    ;; The exporter independently rechecks its actual loaded package too.
    (when (contains? #{:iso-utc :raw-ticks} (:datetime64-wire options))
      (native/ensure-loaded!)
      (when-not (= "26.9.0" (native/chdb-version))
        (throw (ex-info "Timestamp wire requires native package 26.9.0"
                        {:type ::unqualified-timestamp-wire})))))
  options)
