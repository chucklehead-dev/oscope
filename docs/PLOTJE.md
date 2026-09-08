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

A counter editor stores the complete semantic recipe and field bindings, never
the returned points:

```clojure
{:data
 {:source :counter-query
  :query {:mode :counter-series :metric-kind :sum
          :temporality :cumulative :monotonic? true
          :metric-name "http.server.requests"
          :bucket :none :group-by [:service-name]
          :aggregates [:increase :rate] :window :1h :limit 100}
  :select [:service-name :increase :rate :interval-count :reset-count
           :observed-duration-nanos :metric-kind :temporality :monotonic?]}
 :layers [{:mark :bar :x :service-name :y :increase}]}
```

The separate `:histogram-query` source does the same for cumulative explicit
histograms. Its closed recipe retains `:metric-kind :histogram`,
`:temporality :cumulative`, the exact metric name, grouping, bucket, window,
aggregates, and limit. Returned rows expose observation `:count`, `:sum`,
`:avg`, and p50/p95/p99 descriptors. Each selected quantile is flattened into
an estimate, containing lower and upper bounds, exact rank numerator and
denominator, worst-case absolute interpolation error, interpolation method,
and a display bucket. Empty histograms return null average and quantiles.
Implicit infinity-tail buckets have a null estimate and error instead of an
invented scalar. Rows also retain explicit bounds, interval/reset counts,
observed duration, kind, and temporality.

Gauge and sum point values provide `:count`, `:sum`, `:min`, `:max`, `:avg`,
`:p50`, `:p95`, and `:p99`. Delta explicit histograms provide observation
`:count`, `:sum`, and `:avg`. Cumulative explicit histograms use the separate
reset-aware histogram recipe above; they are never approximated from scalar
samples. Scalar sum values are stored-point
aggregates, not counter increases or rates. Select the separate
`:counter-series` mode for those semantics. Its recipe explicitly retains
`:metric-kind :sum`, `:temporality :cumulative`, and `:monotonic? true`, and
offers only `:increase` and `:rate`. Rate is per second over the summed exact
observed interval duration; it is not boundary-extrapolated. Every returned row
also carries `:interval-count`, `:reset-count`, and
`:observed-duration-nanos` plus the three provenance fields. The first
positive-duration interval whose stored start is inside the window counts as a
reset, as does each later interval after `StartTimeUnix` advances.

Counter recipes reject rather than estimate when stored whole-second points do
not prove the result: duplicate timestamps, unexplained decreases, overlapping
epochs, dimension projections that collapse distinct OTEL streams, and
intervals crossing requested bucket boundaries all fail visibly. The first
snapshot is omitted when its start predates the window because its boundary
increase is unknown. A zero-duration zero-valued reset has no rate interval and
is not included in `:reset-count`. Source scan/result rows, bytes, memory,
execution time, window, and output count all retain hard ceilings.

Histogram recipes likewise fail closed on changed boundary schemas, bucket or
count decreases within an epoch, duplicate seconds, overlapping epochs,
projection collapse, bucket-crossing intervals, non-finite data, and missing
evidence. Sum decreases are allowed because cumulative histograms may observe
negative values. Source scans are capped at 10,000 points and 100,000 rows read,
64 MiB read/result bytes, 128 MiB memory, five seconds, and one thread; the
rendered result remains capped at 100 rows and a 24-hour window.

The available buckets are none,
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
selects 1–48 distinct fields. The source is capped at 512 rows; projected values
must be bounded Plotje scalars or a finite explicit-bound vector of at most 64
values, and a missing source or field is a visible spec error. Unknown keys fail
closed. Literal `:data` row vectors remain supported
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

## Redacted query validation events

Hosts and tests may opt into `oscope.query.trace/call-with-event-sink` around a
Plotje preview. It emits a fresh, contiguous `:seq` and the canonical
`:invoke`/`:return`/`:throw` lifecycle used by Hegel semantic trace rules. An
execute operation contains its nested compile lifecycle, so a completed
successful preview has the ordering execute invoke, compile invoke, compile
return, execute return. Capture is only an adapter to a caller-owned event sink;
it does not persist events or initialize another tracing or telemetry system.

Compile success retains only closed grammar choices and counts: signal, window,
bucket, group/filter/series/calculation/output counts. Compile rejection and
execution success or failure retain only closed outcomes; execution failures
are classified as expression or backend failures. Events never contain filter
values, generated SQL or parameters, returned rows or row counts, credentials,
user-chosen series/calculation aliases, exception messages, or exception data.
The sink is observational and fail-open: a sink failure cannot replace the
query result or application exception.

Snapshot the caller-owned sink only after the preview completes, keep it
bounded, then run sequence, closed-lifecycle, synchronous-parentage, and closed
outcome checks offline. The checked-in Hegel property and negative privacy
controls exercise both successful and failed traces; assertions deliberately
run outside the event sink.

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
not an OTel cumulative-counter rate. Calculation chaining remains a follow-up;
cumulative counters and explicit histograms use their dedicated closed sources.
