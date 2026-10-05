(ns oscope.json-backend
  "Startup-only selection of the general exporter JSON backend."
  (:require [jdbc.chdb.json-each-row :as encoder]))

(defn validate! [backend]
  (when-not (#{:configured :native-guarded} backend)
    (throw (ex-info "Unsupported oscope JSON backend"
                    {:oscope.json-backend/error true :type ::invalid-backend})))
  ;; Probe before connection acquisition, typed DDL, SDK or HTTP workers.
  ;; Explicit unavailable selection must not silently fall back.
  (when (= :native-guarded backend)
    (encoder/close! (encoder/open-encoder {:parallelism 1 :json-backend backend})))
  backend)
