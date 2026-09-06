(ns oscope.typed.controls.wrong-limit
  "Mutant: a string limit cannot enter a bounded query plan."
  (:require [typed.clojure :as t]
            [oscope.query :as query]
            [oscope.typed.contracts]))

(t/ann plan [-> oscope.typed.contracts/QueryPlan])
(defn plan []
  (query/compile-query
   {:signal :spans :field :service-name :window :15m :limit "many"}
   2000000000000000000))
