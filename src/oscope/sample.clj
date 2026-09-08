(ns oscope.sample
  "Deterministic data source for native and web adapter development."
  (:require [oscope.query :as query]
            [oscope.view-model :as view-model]))

(def sample-time 2000000000000000000)
(def ^:private values
  {:spans {:service-name [["gateway" 42] ["checkout" 27] ["worker" 13]]
           :span-name [["GET /work" 38] ["model.run" 29] ["queue.take" 15]]
           :span-kind [["server" 42] ["internal" 31] ["client" 9]]
           :status-code [["OK" 73] ["ERROR" 9]]}
   :logs {:service-name [["gateway" 312] ["checkout" 141] ["worker" 88]]
          :severity-text [["INFO" 437] ["WARN" 72] ["ERROR" 32]]
          :event-name [["request.complete" 106] ["work.claimed" 49] ["retry" 17]]}
   :metrics {:service-name [["gateway" 18] ["worker" 12] ["checkout" 6]]
             :metric-name [["http.server.duration" 18]
                           ["queue.depth" 12] ["model.tokens" 6]]}})
(defn- rows [{:keys [signal field limit]}]
  (->> (get-in values [signal field] [["no sample value" 0]])
       (take limit)
       (mapv (fn [[value count]]
               {:signal signal :field field :value value :count count}))))
(defn- series-rows
  [{:keys [group-by bucket aggregates limit]}]
  (let [base [{:bucket-start-unix-nano (- sample-time 600000000000)
               :service-name "checkout" :metric-unit "{job}"
               :scope-name "demo.metrics" :deployment-environment "demo"
               :count 8 :sum 30.0 :min 2.0 :max 6.0 :avg 3.75
               :p50 3.0 :p95 5.8 :p99 6.0}
              {:bucket-start-unix-nano (- sample-time 300000000000)
               :service-name "checkout" :metric-unit "{job}"
               :scope-name "demo.metrics" :deployment-environment "demo"
               :count 8 :sum 42.0 :min 3.0 :max 8.0 :avg 5.25
               :p50 5.0 :p95 7.7 :p99 8.0}
              {:bucket-start-unix-nano (- sample-time 1000000000)
               :service-name "checkout" :metric-unit "{job}"
               :scope-name "demo.metrics" :deployment-environment "demo"
               :count 8 :sum 36.0 :min 2.0 :max 7.0 :avg 4.5
               :p50 4.0 :p95 6.8 :p99 7.0}]
        fields (vec (concat (when (not= :none bucket)
                              [:bucket-start-unix-nano])
                            group-by aggregates))
        candidates (cond
                     (not= :none bucket) base
                     (seq group-by) [(last base)]
                     :else [(select-keys (last base) aggregates)])]
    (->> candidates (take limit) (mapv #(select-keys % fields)))))
(defn screen-for-selection [selection]
  (let [plan (query/compile-query selection sample-time)]
    (view-model/screen
     plan
     (if (= :metric-series (get-in plan [:selection :mode]))
       (series-rows (:selection plan))
       (rows (:selection plan))))))
(def default-screen (screen-for-selection query/default-selection))
