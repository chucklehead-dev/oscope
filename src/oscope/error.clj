(ns oscope.error
  "Stable public descriptors and formatting helpers for errors.")

(def ^:private lifecycle-operations
  #{:stop-ingress :stop-http-executor :shutdown-sdk :close-source
    :close-exporter :checkpoint :flush :close-connection :lifecycle})

(defn lifecycle-failure
  "Return a closed public descriptor for a thrown lifecycle operation.

  The Throwable argument is deliberately ignored. In particular, its message,
  cause, ex-data, stack, request headers, paths, and telemetry values must not
  become reachable from a caller-visible shutdown result."
  [operation _error]
  {:type ::lifecycle-operation-threw
   :operation (if (contains? lifecycle-operations operation)
                operation
                :lifecycle)})

(defn sorted-keys [keys]
  (vec (sort-by str keys)))
