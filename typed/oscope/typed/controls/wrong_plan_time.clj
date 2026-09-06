(ns oscope.typed.controls.wrong-plan-time
  "Mutant: query-plan epoch fields must be integers."
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]))

(t/ann plan oscope.typed.contracts/QueryPlan)
(def plan
  {:oscope.query/version 1
   :selection {:signal :spans :field :service-name :window :15m :limit 12}
   :request {:signal :spans :fields [:service-name]
             :start-unix-nano 1 :end-unix-nano "later" :limit 12}})
