(ns oscope.query
  "Bounded, SQL-free query plans for the embedded telemetry explorer."
  (:require [otel.exporter.chdb.explorer :as explorer]))

(def windows
  {:15m (* 15 60 1000000000)
   :1h  (* 60 60 1000000000)
   :6h  (* 6 60 60 1000000000)
   :24h explorer/max-time-range-nanos})

(def default-selection
  {:signal :spans :field :service-name :window :1h :limit 12})

(def max-result-limit explorer/max-result-limit)
(def max-value-length explorer/max-text-length)
(def ^:private selection-keys #{:signal :field :window :limit})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :oscope.query/error true :type type))))

(defn supported-fields [] (explorer/supported-fields))

(defn normalize-selection [selection]
  (when-not (map? selection)
    (fail! ::invalid-selection "oscope selection must be a map"
           {:selection selection}))
  (when-let [unknown (seq (remove selection-keys (keys selection)))]
    (fail! ::unsupported-selection-key
           "oscope selection contains unsupported keys"
           {:keys (vec (sort-by str unknown))}))
  (let [{:keys [signal field window limit]}
        (merge default-selection selection)
        fields (get (supported-fields) signal)]
    (when-not fields
      (fail! ::unsupported-signal "oscope signal is not supported"
             {:signal signal :supported-signals (vec (keys (supported-fields)))}))
    (when-not (some #{field} fields)
      (fail! ::unsupported-field "oscope field is not supported for this signal"
             {:signal signal :field field :supported-fields fields}))
    (when-not (contains? windows window)
      (fail! ::unsupported-window "oscope query window is not supported"
             {:window window :supported-windows (vec (keys windows))}))
    (when-not (and (integer? limit) (<= 1 limit max-result-limit))
      (fail! ::invalid-limit "oscope result limit is outside the explorer cap"
             {:limit limit :minimum 1 :maximum max-result-limit}))
    {:signal signal :field field :window window :limit limit}))

(defn compile-query [selection end-unix-nano]
  (when-not (and (integer? end-unix-nano)
                 (<= 1 end-unix-nano 9223372036854775807))
    (fail! ::invalid-time "oscope query end must be epoch nanoseconds"
           {:end-unix-nano end-unix-nano}))
  (let [{:keys [signal field window limit] :as selected}
        (normalize-selection selection)
        start (max 0 (- end-unix-nano (get windows window)))]
    {:oscope.query/version 1
     :selection selected
     :request {:signal signal :fields [field]
               :start-unix-nano start :end-unix-nano end-unix-nano
               :limit limit}}))

(defn validate-plan [plan]
  (when-not (and (map? plan) (= 1 (:oscope.query/version plan)))
    (fail! ::invalid-plan "oscope query plan version is not supported"
           {:version (when (map? plan) (:oscope.query/version plan))}))
  (let [expected (compile-query (:selection plan)
                                (get-in plan [:request :end-unix-nano]))]
    (when-not (= expected plan)
      (fail! ::invalid-plan "oscope query plan does not match its selection"
             {:plan plan}))
    plan))

(defn run [connection plan]
  (explorer/top-values connection (:request (validate-plan plan))))
