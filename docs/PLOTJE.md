# Reusable Plotje charts

The chart editor separates a chart's shape from the telemetry values it draws.
Opening **Edit this chart** produces a data reference such as:

```clojure
{:title "Service Name in Spans"
 :data {:source :current-query
        :select [:value :count]}
 :layers [{:mark :bar :x :value :y :count}]}
```

`:current-query` means the bounded distribution selected in the editor URL.
The URL carries the signal, grouping field, time window, and result limit. The
chart chooses only the fields it needs. Oscope runs that query again for each
preview, projects the selected fields, validates the resulting rows, and only
then renders SVG. Returned service names, metric names, counts, and other sample
values never become part of the editable chart text, so the same text keeps
working as telemetry changes.

The current query produces one row per selected field value:

| Field | Meaning |
| --- | --- |
| `:value` | The selected dimension value, such as a service or metric name. |
| `:count` | The number of matching telemetry records in the selected window. |

Data references are deliberately small. A reference names one source and
selects 1–16 distinct fields. The source is capped at 512 rows, projected values
must be bounded Plotje scalars, and a missing source or field is a visible spec
error. Unknown keys fail closed. Literal `:data` row vectors remain supported
for examples, fixed thresholds, and hand-authored charts.

## Planned query and transform grammar

Useful observability charts need more than a distribution count. The next query
contract should extend the data reference declaratively, with closed choices
for:

- dimensions and grouping;
- fixed time buckets;
- `count`, `sum`, `min`, `max`, and `avg`; and
- bounded percentile selections such as p50, p95, and p99.

Those operations should compile into oscope's parameterized telemetry query
layer and return named fields to Plotje. They should not be arbitrary Clojure,
JavaScript, SQL, or a client-side expression evaluator. This first slice does
not accept transform or aggregate keys: it exposes the distribution query that
oscope already supports and rejects grammar that has no bounded server-side
implementation yet.
