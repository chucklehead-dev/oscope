(ns oscope.typed.contracts
  "External Typed Clojure annotations for oscope's pure data contracts."
  (:require [typed.clojure :as t]
            [oscope.command]
            [oscope.query]
            [oscope.query.expression]
            [oscope.view-model]))

(t/defalias Window (t/U ':15m ':1h ':6h ':24h))
(t/defalias SpanField
  (t/U ':service-name ':span-name ':span-kind ':status-code ':scope-name
       ':http-request-method ':http-response-status-code
       ':deployment-environment))
(t/defalias LogField
  (t/U ':service-name ':severity-text ':event-name ':scope-name
       ':deployment-environment))
(t/defalias MetricField
  (t/U ':service-name ':metric-name ':metric-unit ':scope-name
       ':deployment-environment))
(t/defalias Field (t/U SpanField LogField MetricField))

(t/defalias Signal (t/U ':spans ':logs ':metrics))
(t/defalias Bucket (t/U ':none ':1m ':5m ':15m ':1h))
(t/defalias BucketedTelemetryExpression
  (t/HMap :mandatory {:bucket Bucket}
          :complete? false))
(t/defalias Selection
  (t/HMap :mandatory {:signal Signal :field Field
                       :window Window :limit t/AnyInteger}
          :complete? true))

(t/defalias MetricKind (t/U ':gauge ':sum ':histogram))
(t/defalias MetricGroup
  (t/U ':service-name ':metric-unit ':scope-name ':deployment-environment))
(t/defalias MetricAggregate
  (t/U ':count ':sum ':min ':max ':avg ':p50 ':p95 ':p99))
(t/defalias CounterAggregate (t/U ':increase ':rate))
(t/defalias MetricSeriesSelection
  (t/HMap :mandatory {:mode ':metric-series
                       :metric-kind MetricKind
                       :metric-name t/Str
                       :group-by (t/Vec MetricGroup)
                       :bucket Bucket
                       :aggregates (t/Vec MetricAggregate)
                       :window Window
                       :limit t/AnyInteger}
          :complete? true))
(t/defalias CounterSeriesSelection
  (t/HMap :mandatory {:mode ':counter-series
                       :metric-kind ':sum
                       :temporality ':cumulative
                       :monotonic? true
                       :metric-name t/Str
                       :group-by (t/Vec MetricGroup)
                       :bucket Bucket
                       :aggregates (t/Vec CounterAggregate)
                       :window Window
                       :limit t/AnyInteger}
          :complete? true))
(t/defalias QuerySelection
  (t/U Selection MetricSeriesSelection CounterSeriesSelection))

(t/defalias RequestId
  (t/U t/AnyInteger
       t/Kw
       (t/HVec [t/Kw (t/U t/AnyInteger QuerySelection)])))

(t/defalias QueryRequest
  (t/HMap :mandatory {:signal Signal
                       :fields (t/HVec [Field])
                       :start-unix-nano t/AnyInteger
                       :end-unix-nano t/AnyInteger
                       :limit t/AnyInteger}
          :complete? true))
(t/defalias MetricSeriesRequest
  (t/HMap :mandatory {:metric-kind MetricKind
                       :metric-name t/Str
                       :group-by (t/Vec MetricGroup)
                       :bucket Bucket
                       :aggregates (t/Vec MetricAggregate)
                       :start-unix-nano t/AnyInteger
                       :end-unix-nano t/AnyInteger
                       :limit t/AnyInteger}
          :complete? true))
(t/defalias CounterSeriesRequest
  (t/HMap :mandatory {:metric-kind ':sum
                       :temporality ':cumulative
                       :monotonic? true
                       :metric-name t/Str
                       :group-by (t/Vec MetricGroup)
                       :bucket Bucket
                       :aggregates (t/Vec CounterAggregate)
                       :start-unix-nano t/AnyInteger
                       :end-unix-nano t/AnyInteger
                       :limit t/AnyInteger}
          :complete? true))
(t/defalias AnyQueryRequest
  (t/U QueryRequest MetricSeriesRequest CounterSeriesRequest))
(t/defalias QueryPlan
  (t/HMap :mandatory {:oscope.query/version (t/Val 1)
                       :selection QuerySelection
                       :request AnyQueryRequest}
          :complete? true))
(t/defalias QueryCommand
  (t/HMap :mandatory {:oscope.command/version (t/Val 1)
                       :command/type ':query
                       :request-id RequestId
                       :selection QuerySelection}
          :complete? true))

(t/defalias RawDistributionRow
  (t/HMap :mandatory {:signal Signal
                       :field Field :value t/Str :count t/AnyInteger}
          :complete? true))
(t/defalias DistributionRow
  (t/HMap :mandatory {:value t/Str :count t/AnyInteger}
          :complete? true))

(t/ann oscope.query/max-time-range-nanos t/AnyInteger)
(t/ann oscope.query/max-result-limit t/AnyInteger)
(t/ann oscope.query/max-value-length t/AnyInteger)
(t/ann oscope.query/fields (t/Map Signal (t/Vec Field)))
(t/ann oscope.query/windows (t/Map Window t/AnyInteger))
(t/ann oscope.query/default-selection Selection)
(t/ann oscope.query/default-metric-series-selection MetricSeriesSelection)
(t/ann oscope.query/default-counter-series-selection CounterSeriesSelection)
(t/ann oscope.query.expression/bucket-presets
  (t/Map Bucket (t/Option t/AnyInteger)))
(t/ann oscope.query/supported-fields
  [-> (t/Map Signal (t/Vec Field))])
(t/ann oscope.query/window-nanos [Window -> t/AnyInteger])
(t/ann oscope.query/normalize-selection [QuerySelection -> QuerySelection])
(t/ann oscope.query/compile-query
  [QuerySelection t/AnyInteger -> QueryPlan])
(t/ann oscope.query/validate-plan [QueryPlan -> QueryPlan])

(t/ann oscope.command/query-command
  [RequestId QuerySelection -> QueryCommand])

(t/ann oscope.view-model/normalize-rows
  [Selection (t/Vec RawDistributionRow) -> (t/Vec DistributionRow)])
