(ns oscope.typed.controls.wrong-window
  "Mutant: an unbounded window cannot enter a bounded query plan."
  (:require [typed.clojure :as t]
            [oscope.query :as query]
            [oscope.typed.contracts]))

(t/ann plan [-> oscope.typed.contracts/QueryPlan])
(defn plan []
  (query/compile-query
   {:signal :spans :field :service-name :window :forever :limit 12}
   2000000000000000000))
