(ns oscope.typed.controls.wrong-counter-provenance
  "Mutation control: a counter recipe cannot claim delta temporality."
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]))

(t/ann invalid-counter oscope.typed.contracts/CounterSeriesSelection)
(def invalid-counter
  {:mode :counter-series
   :metric-kind :sum
   :temporality :delta
   :monotonic? true
   :metric-name "requests.total"
   :group-by [:service-name]
   :bucket :5m
   :aggregates [:increase :rate]
   :window :1h
   :limit 100})
