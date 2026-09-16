(ns oscope.error
  "Stable public descriptors and formatting helpers for errors.")

(def ^:private lifecycle-operations
  #{:stop-ingress :stop-http-executor :stop-listener :stop-query-workers
    :shutdown-sdk :close-source
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

(def ^:private durability-categories
  {:jdbc.chdb.durable.control/lease-fenced :lease-fenced
   :jdbc.chdb.durable.control/commit-ambiguous :commit-ambiguous
   :jdbc.chdb.durable.control/timeout :storage-timeout
   :jdbc.chdb.durable.s3/authentication :storage-authentication
   :jdbc.chdb.durable.s3/permission :storage-permission
   :jdbc.chdb.durable.s3/throttled :storage-throttled
   :jdbc.chdb.durable.s3/transport :storage-transport
   :jdbc.chdb.durable.s3/timeout :storage-timeout
   :jdbc.chdb.durable.s3/provider :storage-provider
   :jdbc.chdb.durable.s3/invalid-response :storage-response
   :oscope.server/durability-unconfirmed :unconfirmed})

(defn durability-boundary-observation
  "Closed diagnostic display only; no internal persistence subphase is inferred.
  Native query failures without a known typed cause remain unclassified; the
  generic :jdbc/sql-error flag is not evidence of a specific native phase."
  [operation outcome failure]
  {:oscope.durability-boundary/version 1
   ;; Like failure types, arbitrary operation values must never be hashed.
   :operation (if (and (keyword? operation)
                       (contains? #{:flush :checkpoint} operation))
                operation :unknown)
   :phase :after-export-before-http-ack
   :outcome (if (= :confirmed outcome) :confirmed :failed)
   :category
   (if (= :confirmed outcome)
     :none
     (loop [current failure remaining 8]
       (if (and current (pos? remaining))
         (let [type (:type (ex-data current))]
           ;; Never hash/render arbitrary caller-controlled discriminator values.
           (or (when (keyword? type) (get durability-categories type))
               (recur (ex-cause current) (dec remaining))))
         :unclassified)))})
