# Reusable Plotje charts

The chart editor separates a chart's shape from the telemetry values it draws.
Opening **Edit this chart** produces a data reference such as:

```clojure
{:title "Service Name in Spans"
 :data {:source :telemetry-query
        :query {:signal :spans :window :1h
                :group-by [:service-name] :filters []
                :series [{:as :count :op :count}]
                :limit 12}
        :select [:service-name :count]}
 :layers [{:mark :bar :x :service-name :y :count}]}
```

The current editor now writes the complete bounded query rather than relying on
returned values:

```clojure
{:data
 {:source :telemetry-query
  :query {:signal :spans
          :window :15m
          :group-by [:service-name]
          :filters [{:field :status-code :op :eq :value "ERROR"}]
          :series [{:as :requests :op :count}
                   {:as :average-ns :op :avg :field :duration-ns}
                   {:as :p95-ns :op :percentile
                    :field :duration-ns :percentile 95}]
          :limit 20}
  :select [:service-name :requests :average-ns :p95-ns]}
 :layers [{:mark :bar :x :service-name :y :p95-ns}]}
```

Oscope runs this query again for each preview, projects the selected fields,
validates the resulting rows, and only then renders SVG. Returned service names,
metric names, counts, and other samples never become part of the editable chart
text, so the same text keeps working as telemetry changes.

The legacy `:current-query` source remains available for the bounded query
recipe selected in the editor URL. It is used for all current metric screens:
their gauge/sum/histogram provenance and optional fixed time bucket are not yet
part of the reusable gauge-only `:telemetry-query` grammar. This preserves the
original result semantics across a no-edit preview. In both forms, the chart
selects named result fields and never copies returned data points into editable
text.

The current query produces one row per selected field value:

| Field | Meaning |
| --- | --- |
| Group field | The selected dimension, such as `:service-name` or `:metric-name`. |
| Series alias | The result of its aggregate, such as `:count` or `:p95-ns`. |
| Calculation alias | Arithmetic derived from two named aggregate series or constants. |

A metric recipe adds an exact metric name and physical kind, an optional fixed
time bucket, zero or one allowlisted chart dimension, and one to four named
aggregates. For example:

```clojure
{:title "Queue depth by service"
 :data {:source :current-query
        :select [:bucket-start-unix-nano :service-name :avg :p95]}
 :layers [{:mark :line
           :x :bucket-start-unix-nano :y :p95 :color :service-name}]}
```

Gauge and sum point values provide `:count`, `:sum`, `:min`, `:max`, `:avg`,
`:p50`, `:p95`, and `:p99`. Delta explicit histograms provide observation
`:count`, `:sum`, and `:avg`. Cumulative histogram snapshots are excluded until
reset-aware differencing is implemented. Scalar sum values are stored-point
aggregates, not counter increases or rates. The available buckets are none,
1 minute, 5 minutes, 15 minutes, and 1 hour; dimensions are service, unit,
scope, and deployment environment.

The generated chart draws the first aggregate in canonical order so its visual
meaning remains clear without a legend. Its data reference includes every
returned table field, so the editor can switch `:y` from `:avg` to `:p95` (or
another selected aggregate), or add explicitly styled layers without embedding
the returned values. The shared query library supports more dimensions, but
oscope waits for a composite-series and legend contract before exposing them;
otherwise distinct SQL groups could be drawn as one line.

Data references are deliberately small. A reference names one source and
selects 1–16 distinct fields. The source is capped at 512 rows, projected values
must be bounded Plotje scalars, and a missing source or field is a visible spec
error. Unknown keys fail closed. Literal `:data` row vectors remain supported
for examples, fixed thresholds, and hand-authored charts.

## Query and aggregate grammar

The server accepts up to two group fields, four equality filters, eight series,
and 100 result rows over a 15 minute, 1 hour, 6 hour, or 24 hour window. Series
support:

- `:count`, which has no numeric field;
- `:sum`, `:avg`, `:min`, and `:max` over an allowlisted numeric field; and
- `:percentile` with 50, 75, 90, 95, or 99.

Span queries aggregate `:duration-ns`; log queries aggregate
`:severity-number`; reusable metric expressions aggregate gauge `:value` only.
They deliberately do not union sum and histogram rows: correct aggregation of
those signals must retain temporality, reset, bucket, and optional-extrema
provenance. The URL-owned metric-series source above remains available for its
current kind-aware operations while that richer reusable contract is designed.

All tables, columns, aggregate functions, percentile constants, and aliases in
SQL come from closed server allowlists. Times, filter values, and limits are
parameters. Query expressions cannot contain Clojure, JavaScript, SQL, regular
expressions, or arbitrary functions. Every expression also installs hard chDB
ceilings of 100,000 source rows, 64 MiB read, 128 MiB query memory, five seconds,
and one execution thread. Exceeding a ceiling fails the preview rather than
silently returning an unbounded or partial computation. The result row limit is
separate and does not stand in for those execution bounds.

Calculations combine two aggregate results with a small typed server-side AST.
For example, if `:errors` and `:requests` are aggregate series:

```clojure
:calculations [{:as :error-rate
                :op :divide
                :args [:errors :requests]}]
```

An expression accepts at most six calculations. `:op` is `:add`, `:subtract`,
`:multiply`, or `:divide`; `:args` contains exactly two aggregate-series aliases
or finite numeric constants. A calculation alias must be unique and cannot
replace a group or aggregate alias. Calculations cannot refer to other
calculations in this first slice, which makes ordering and cycles impossible.

Calculation results are doubles. A null aggregate operand propagates to a null
result, division by positive or negative zero returns null, and overflow to a
non-finite result returns null. Missing aggregate columns and non-numeric
non-null aggregate values are server contract errors. Oscope computes these
values after the bounded aggregate query in its trusted server adapter; it does
not put arithmetic in editable JavaScript, accept arbitrary functions, or
insert resolved values into the Plotje text.

The division primitive supports error-rate-style charts once both numerator
and denominator aggregate series exist. Per-series filters are not in the
current grammar, so independently filtering an `:errors` numerator and a
`:requests` denominator within one expression remains follow-up work. Fixed
time buckets, calculation chaining, OTel cumulative-counter rate/reset logic,
and histogram reconstruction also remain follow-ups.
