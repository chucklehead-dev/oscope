# Reusable Plotje charts

The chart editor separates a chart's shape from the telemetry values it draws.
Opening **Edit this chart** produces a data reference such as:

```clojure
{:title "Service Name in Spans"
 :data {:source :current-query
        :select [:value :count]}
 :layers [{:mark :bar :x :value :y :count}]}
```

`:current-query` means the bounded distribution or metric-series recipe selected
in the editor URL. The chart chooses only the named result fields it needs.
Oscope runs that query again for each preview, projects those fields, validates
the resulting rows, and only then renders SVG. Returned service names, metric
names, counts, percentiles, and other sample values never become part of the
editable chart text, so the same text keeps working as telemetry changes.

The current query produces one row per selected field value:

| Field | Meaning |
| --- | --- |
| `:value` | The selected dimension value, such as a service or metric name. |
| `:count` | The number of matching telemetry records in the selected window. |

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

## Query boundary and future transforms

The implemented operations compile into oscope's parameterized telemetry query
layer and return named fields to Plotje. They are not arbitrary Clojure,
JavaScript, SQL, or a client-side expression evaluator. Future transforms such
as rates, cumulative histogram differencing, merged histogram percentiles, and
arithmetic across result fields need similarly closed semantics and bounded
server-side implementations before they become part of this grammar.
