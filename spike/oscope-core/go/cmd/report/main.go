// report opens the store the shop wrote and prints what was recorded.
package main

import (
	"fmt"
	"os"

	"github.com/chucklehead-dev/oscope/spike/oscope-core/go/oscope"
)

func q(title, sql string) {
	out, err := oscope.Query(sql, "PrettyCompactMonoBlock")
	if err != nil {
		fmt.Fprintln(os.Stderr, title, err)
		os.Exit(1)
	}
	fmt.Printf("\n%s\n%s", title, out)
}

func main() {
	db := "./oscope-data"
	if len(os.Args) > 1 {
		db = os.Args[1]
	}
	if err := oscope.Start(oscope.Config{DBPath: db, Service: "report"}); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer oscope.Stop()

	q("Spans by name, kind and status", `
SELECT SpanName, SpanKind, StatusCode, count() AS spans,
       round(quantile(0.5)(Duration) / 1e3) AS p50_us,
       round(quantile(0.99)(Duration) / 1e3) AS p99_us
FROM otel_traces GROUP BY ALL ORDER BY spans DESC, SpanName`)

	q("Parent linkage: every non-root span's parent is in the table", `
SELECT c.SpanKind AS child_kind, p.SpanKind AS parent_kind, count() AS links
FROM otel_traces c INNER JOIN otel_traces p ON c.ParentSpanId = p.SpanId AND c.TraceId = p.TraceId
GROUP BY ALL ORDER BY links DESC`)

	q("Orphans (parent id set but parent span missing)", `
SELECT count() AS orphans FROM otel_traces
WHERE ParentSpanId != '' AND ParentSpanId NOT IN (SELECT SpanId FROM otel_traces)`)

	q("Logs, correlated to the span they were written in", `
SELECT l.SeverityText, l.Body, t.SpanName, count() AS logs
FROM otel_logs l LEFT JOIN otel_traces t ON l.SpanId = t.SpanId AND l.TraceId = t.TraceId
GROUP BY ALL ORDER BY logs DESC`)

	q("One failed order: client call -> server -> function spans", `
WITH (SELECT (TraceId, ParentSpanId) FROM otel_traces
      WHERE SpanName = 'load-order' AND StatusCode = 'Error' LIMIT 1) AS failed,
     (SELECT ParentSpanId FROM otel_traces WHERE SpanId = failed.2 LIMIT 1) AS client_span
SELECT repeat('  ', toUInt8(SpanKind = 'Server') + 2 * toUInt8(SpanKind = 'Internal')) || SpanName AS span,
       SpanKind, StatusCode, round(Duration / 1e3) AS us,
       SpanAttributes['http.response.status_code'] AS http_status,
       SpanAttributes['exception.message'] AS error
FROM otel_traces
WHERE TraceId = failed.1 AND (SpanId IN (client_span, failed.2) OR ParentSpanId = failed.2)
ORDER BY Timestamp`)
}
