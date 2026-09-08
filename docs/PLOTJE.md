# Reusable Plotje charts

The chart editor separates a chart's shape from the telemetry values it draws.
Opening **Edit this chart** produces a data reference such as:

```clojure
{:title "Service Name in Spans"
 :data {:source :telemetry-query
        :query {:signal :spans :window :1h :bucket :none
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
          :bucket :5m
          :group-by [:service-name]
          :filters [{:field :status-code :op :eq :value "ERROR"}]
          :series [{:as :requests :op :count}
                   {:as :average-ns :op :avg :field :duration-ns}
                   {:as :p95-ns :op :percentile
                    :field :duration-ns :percentile 95}]
          :limit 20}
  :select [:bucket-start-unix-nano :service-name
           :requests :average-ns :p95-ns]}
 :layers [{:mark :line :x :bucket-start-unix-nano
           :y :p95-ns :color :service-name}]}
```

Oscope runs this query again for each preview, projects the selected fields,
validates the resulting rows, and only then renders SVG. Returned service names,
metric names, counts, and other samples never become part of the editable chart
text, so the same text keeps working as telemetry changes.

The reusable query accepts `:none`, `:1m`, `:5m`, `:15m`, or `:1h`. A fixed
bucket adds `:bucket-start-unix-nano` as the first output field and groups every
aggregate at Unix-epoch-aligned boundaries. Global filters choose the source
rows first; per-series filters then apply inside each bucket and group;
calculations operate on the aggregate results from that same row. The bucket
interval, times, filters, display lengths, and result limit are parameters.

The legacy `:current-query` source remains available for the bounded query
recipe selected in the editor URL. It is used for current metric screens whose
gauge/sum/histogram physical-kind provenance is richer than the reusable
gauge-only `:telemetry-query` grammar. This preserves the original result
semantics across a no-edit preview. In both forms, the chart selects named
result fields and never copies returned data points into editable text.

The current query produces one row per selected field value:

| Field | Meaning |
| --- | --- |
| `:bucket-start-unix-nano` | Epoch-aligned start of a requested fixed bucket; omitted for `:none`. |
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

The server accepts one fixed bucket preset, up to two group fields, four global
equality filters, eight series, and 100 result rows over a 15 minute, 1 hour,
6 hour, or 24 hour window. Omitting `:bucket` is equivalent to `:none` for older
saved expressions. Bucketed rows are ordered by bucket start and then group
dimensions; unbucketed rows retain primary-aggregate ordering.
Each series accepts up to two additional equality filters, with at most eight
series filters across the whole expression. Series support:

- `:count`, which has no numeric field;
- `:sum`, `:avg`, `:min`, and `:max` over an allowlisted numeric field; and
- `:percentile` with 50, 75, 90, 95, or 99.

Global filters restrict the source rows seen by every aggregate. A series filter
is an additional `AND` predicate applied only to that aggregate. This makes a
filtered numerator and unfiltered denominator explicit:

```clojure
:filters [{:field :span-name :op :eq :value "request"}]
:series [{:as :requests :op :count}
         {:as :errors :op :count
          :filters [{:field :status-code :op :eq :value "ERROR"}]}]
:calculations [{:as :error-rate
                :op :divide
                :args [:errors :requests]}]
```

Here both counts include only `request` spans, while only `:errors` also
requires error status. Filter fields come from the signal's dimension
allowlist, the only operator is `:eq`, and values remain bounded SQL parameters.
Series filters work with every supported aggregate, including percentiles.
When no rows match a series filter, filtered `:count` returns zero and filtered
numeric aggregates return null. Calculations then use their documented null
propagation and division-by-zero rules.

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

Together with per-series filters, division supports error-rate-style charts
over stored span or log events. With a fixed bucket this is a per-bucket ratio,
not an OTel cumulative-counter rate. Calculation chaining, cumulative-counter
rate/reset logic, and histogram reconstruction remain follow-ups.
