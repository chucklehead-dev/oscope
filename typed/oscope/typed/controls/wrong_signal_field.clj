(ns oscope.typed.controls.wrong-signal-field
  "Mutant: an unsupported signal cannot enter a query selection."
  (:require [typed.clojure :as t]
            [oscope.query :as query]
            [oscope.typed.contracts]))

(t/ann plan [-> oscope.typed.contracts/QueryPlan])
(defn plan []
  (query/compile-query
   {:signal :unknown :field :span-name :window :15m :limit 12}
   2000000000000000000))
