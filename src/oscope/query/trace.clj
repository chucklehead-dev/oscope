(ns oscope.query.trace
  "Opt-in, privacy-shaped semantic events for Plotje query operations.

  The emitted maps use the same invoke/return/throw lifecycle consumed by
  Hegel's semantic trace rules. This namespace only adapts operations to an
  event sink; it does not own persistence, export, or a second telemetry SDK.")

(def ^:dynamic *event-sink* nil)
(def ^:dynamic *trace-state* nil)
(def ^:dynamic *parent-operation-id* nil)

(defn call-with-event-sink
  "Run `f` with an event sink and a fresh, request-local sequence.

  `sink` receives already-redacted event maps. Sink failures are ignored so
  optional validation capture cannot change query behavior."
  [sink f]
  (when-not (ifn? sink)
    (throw (ex-info "Plotje query trace sink must be callable"
                    {:type ::invalid-event-sink})))
  (binding [*event-sink* sink
            *trace-state* (atom {:next-seq 1 :next-operation-id 0})
            *parent-operation-id* nil]
    (f)))

(defn- allocate! []
  (let [allocated (atom nil)]
    (swap! *trace-state*
           (fn [{:keys [next-seq next-operation-id] :as state}]
             (reset! allocated {:seq next-seq
                                :operation-id next-operation-id})
             (assoc state :next-seq (inc next-seq)
                          :next-operation-id (inc next-operation-id))))
    @allocated))

(defn- next-seq! []
  (let [allocated (atom nil)]
    (swap! *trace-state*
           (fn [{:keys [next-seq] :as state}]
             (reset! allocated next-seq)
             (assoc state :next-seq (inc next-seq))))
    @allocated))

(defn- emit! [event]
  (try
    (*event-sink* event)
    (catch Throwable _ nil)))

(defn invoke!
  "Run one query operation and emit a redacted canonical lifecycle.

  `return-value` and `throw-value` must derive only closed, bounded metadata;
  they must never inspect SQL, parameters, rows, aliases, credentials, or
  exception messages/data. With no bound sink this is exactly `proceed`."
  [operation return-value throw-value proceed]
  (if (or (nil? *event-sink*) (nil? *trace-state*))
    (proceed)
    (let [{:keys [seq operation-id]} (allocate!)
          parent-operation-id *parent-operation-id*]
      (emit! {:oscope.query-trace/version 1
              :seq seq
              :operation-id operation-id
              :parent-operation-id parent-operation-id
              :phase :invoke
              :operation operation})
      (try
        (let [result (binding [*parent-operation-id* operation-id]
                       (proceed))]
          (emit! {:oscope.query-trace/version 1
                  :seq (next-seq!)
                  :operation-id operation-id
                  :phase :return
                  :value (return-value result)})
          result)
        (catch Throwable error
          (emit! {:oscope.query-trace/version 1
                  :seq (next-seq!)
                  :operation-id operation-id
                  :phase :throw
                  :value (throw-value error)})
          (throw error))))))
