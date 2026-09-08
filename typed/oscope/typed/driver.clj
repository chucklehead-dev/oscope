(ns oscope.typed.driver
  "Small valid consumer proving the external contract annotations are used."
  (:require [typed.clojure :as t]
            [oscope.command :as command]
            [oscope.query :as query]
            [oscope.typed.contracts]
            [oscope.view-model :as view-model]))

(t/ann selection oscope.typed.contracts/Selection)
(def selection
  {:signal :spans :field :service-name :window :15m :limit 12})

(t/ann plan oscope.typed.contracts/QueryPlan)
(def plan (query/compile-query selection 2000000000000000000))

(t/ann query-command oscope.typed.contracts/QueryCommand)
(def query-command (command/query-command [:web-refresh 7] selection))

(t/ann raw-rows (t/Vec oscope.typed.contracts/RawDistributionRow))
(def raw-rows
  [{:signal :spans :field :service-name :value "checkout" :count 3}])

(t/ann rows (t/Vec oscope.typed.contracts/DistributionRow))
(def rows (view-model/normalize-rows selection raw-rows))

(t/ann bucketed-expression oscope.typed.contracts/BucketedTelemetryExpression)
(def bucketed-expression
  {:signal :metrics :window :15m :bucket :5m
   :group-by [:service-name] :filters []
   :series [{:as :average :op :avg :field :value}]
   :limit 20})
