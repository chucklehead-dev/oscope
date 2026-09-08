(ns oscope.typed.controls.wrong-histogram-temporality
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]))

(t/ann invalid-histogram
  oscope.typed.contracts/CumulativeHistogramSeriesSelection)
(def invalid-histogram
  {:mode :cumulative-histogram-series
   :metric-kind :histogram
   :temporality :delta
   :metric-name "request.duration"
   :group-by [:service-name]
   :bucket :none
   :aggregates [:count :p95]
   :window :1h
   :limit 100})
