(ns oscope.typed.controls.wrong-view-row
  "Mutant: renderer-independent distribution counts must be integers."
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]
            [oscope.view-model :as view-model]))

(t/ann rows [-> (t/Vec oscope.typed.contracts/DistributionRow)])
(defn rows []
  (view-model/normalize-rows
   {:signal :spans :field :service-name :window :15m :limit 12}
   [{:signal :spans :field :service-name :value "checkout" :count "three"}]))
