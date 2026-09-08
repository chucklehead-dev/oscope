(ns oscope.typed.controls.wrong-bucket
  "Mutant: an arbitrary interval cannot enter the fixed-bucket expression contract."
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]))

(t/ann expression oscope.typed.contracts/BucketedTelemetryExpression)
(def expression
  {:signal :metrics :window :15m :bucket :30s
   :group-by [:service-name] :filters []
   :series [{:as :average :op :avg :field :value}]
   :limit 20})
