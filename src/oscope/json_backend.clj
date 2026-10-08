(ns oscope.json-backend
  "Startup-only selection of the general exporter JSON backend."
  (:require [jdbc.chdb.json-each-row :as encoder]
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
