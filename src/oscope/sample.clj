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
(defn screen-for-selection [selection]
  (let [plan (query/compile-query selection sample-time)]
    (view-model/screen plan (rows (:selection plan)))))
(def default-screen (screen-for-selection query/default-selection))
