//! JSON API handlers. Every query is built from the compiled search filter
//! (query.rs), validated numbers, and allowlisted identifiers.

use crate::query::{compile, sql_str, Source};
use crate::{now_ms, App};
use serde_json::{json, Value};
use std::collections::HashMap;

type P = HashMap<String, String>;

struct Window {
    from: i64,
    to: i64,
}

impl Window {
    fn from_params(p: &P) -> Window {
        let to = p.get("to").and_then(|v| v.parse().ok()).unwrap_or_else(now_ms);
        let from = p.get("from").and_then(|v| v.parse().ok()).unwrap_or(to - 15 * 60 * 1000);
        Window { from: from.min(to - 1000), to }
    }
    fn sql(&self) -> String {
        format!(
            "Timestamp >= fromUnixTimestamp64Milli(toInt64({})) AND Timestamp < fromUnixTimestamp64Milli(toInt64({}))",
            self.from, self.to
        )
    }
    /// Histogram step: ~60 buckets, whole seconds.
    fn step_s(&self) -> i64 {
        (((self.to - self.from) as f64 / 1000.0 / 60.0).ceil() as i64).max(1)
    }
}

fn source(p: &P) -> Source {
    Source::parse(p.get("source").map(|s| s.as_str()).unwrap_or("logs"))
}

fn table(app: &App, s: Source) -> &'static str {
    if s == Source::Logs { app.logs } else { app.traces }
}

fn filter(p: &P, s: Source) -> Result<String, String> {
    compile(p.get("q").map(|s| s.as_str()).unwrap_or(""), s)
}

fn count(app: &App, sql: &str) -> Result<u64, String> {
    let rows = app.rows(sql)?;
    Ok(rows.first().and_then(|r| r.get("c")).and_then(|c| c.as_str().and_then(|s| s.parse().ok()).or(c.as_u64())).unwrap_or(0))
}

pub fn search(app: &App, p: &P) -> Result<Value, String> {
    let src = source(p);
    let w = Window::from_params(p);
    let f = filter(p, src)?;
    let limit: u32 = p.get("limit").and_then(|v| v.parse().ok()).unwrap_or(200).min(1000);
    let t = table(app, src);
    let cols = match src {
        Source::Logs => "toUnixTimestamp64Milli(Timestamp) AS ts, ServiceName AS service, SeverityText AS level, Body AS body, TraceId AS trace_id, SpanId AS span_id, LogAttributes AS attributes, ResourceAttributes AS resource",
        Source::Traces => "toUnixTimestamp64Milli(Timestamp) AS ts, ServiceName AS service, SpanName AS name, SpanKind AS kind, StatusCode AS status, StatusMessage AS status_message, round(Duration / 1e6, 3) AS duration_ms, TraceId AS trace_id, SpanId AS span_id, ParentSpanId AS parent_span_id, SpanAttributes AS attributes, ResourceAttributes AS resource",
    };
    let rows = app.rows(&format!("SELECT {cols} FROM {t} WHERE {} AND {f} ORDER BY Timestamp DESC LIMIT {limit}", w.sql()))?;
    let total = count(app, &format!("SELECT count() AS c FROM {t} WHERE {} AND {f}", w.sql()))?;
    Ok(json!({ "rows": rows, "total": total, "from": w.from, "to": w.to }))
}

pub fn histogram(app: &App, p: &P) -> Result<Value, String> {
    let src = source(p);
    let w = Window::from_params(p);
    let f = filter(p, src)?;
    let step = w.step_s();
    let g = if src == Source::Logs { "upper(SeverityText)" } else { "StatusCode" };
    let rows = app.rows(&format!(
        "SELECT toUnixTimestamp(toStartOfInterval(Timestamp, INTERVAL {step} SECOND)) * 1000 AS t, {g} AS g, count() AS c \
         FROM {} WHERE {} AND {f} GROUP BY t, g ORDER BY t",
        table(app, src),
        w.sql()
    ))?;
    Ok(json!({ "step_ms": step * 1000, "from": w.from, "to": w.to, "buckets": rows }))
}

pub fn facets(app: &App, p: &P) -> Result<Value, String> {
    let src = source(p);
    let w = Window::from_params(p);
    let f = filter(p, src)?;
    let t = table(app, src);
    let base = format!("FROM {t} WHERE {} AND {f}", w.sql());
    let fixed: &[(&str, &str)] = match src {
        Source::Logs => &[("service", "ServiceName"), ("level", "SeverityText")],
        Source::Traces => &[("service", "ServiceName"), ("status", "StatusCode"), ("kind", "SpanKind"), ("name", "SpanName")],
    };
    let mut out = Vec::new();
    for (field, col) in fixed {
        let values = app.rows(&format!("SELECT {col} AS v, count() AS c {base} GROUP BY v ORDER BY c DESC, v LIMIT 8"))?;
        out.push(json!({ "field": field, "values": values }));
    }
    let map = if src == Source::Logs { "LogAttributes" } else { "SpanAttributes" };
    let keys = app.rows(&format!("SELECT arrayJoin(mapKeys({map})) AS k, count() AS c {base} GROUP BY k ORDER BY c DESC, k LIMIT 6"))?;
    for k in keys {
        let Some(key) = k.get("k").and_then(|v| v.as_str()) else { continue };
        let values = app.rows(&format!(
            "SELECT {map}[{0}] AS v, count() AS c {base} AND mapContains({map}, {0}) GROUP BY v ORDER BY c DESC, v LIMIT 6",
            sql_str(key)
        ))?;
        out.push(json!({ "field": key, "values": values }));
    }
    Ok(json!({ "facets": out }))
}

pub fn trace(app: &App, id: &str) -> Result<Value, String> {
    if id.len() != 32 || !id.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("trace id must be 32 hex characters".into());
    }
    let tid = sql_str(id);
    let spans = app.rows(&format!(
        "SELECT toUnixTimestamp64Nano(Timestamp) AS start_ns, Duration AS dur_ns, ServiceName AS service, SpanName AS name, \
         SpanKind AS kind, StatusCode AS status, StatusMessage AS status_message, SpanId AS span_id, ParentSpanId AS parent_span_id, \
         SpanAttributes AS attributes FROM {} WHERE TraceId = {tid} ORDER BY Timestamp LIMIT 5000",
        app.traces
    ))?;
    let logs = app.rows(&format!(
        "SELECT toUnixTimestamp64Nano(Timestamp) AS ts_ns, ServiceName AS service, SeverityText AS level, Body AS body, \
         SpanId AS span_id, LogAttributes AS attributes FROM {} WHERE TraceId = {tid} ORDER BY Timestamp LIMIT 5000",
        app.logs
    ))?;
    Ok(json!({ "trace_id": id, "spans": spans, "logs": logs }))
}

pub fn services(app: &App, p: &P) -> Result<Value, String> {
    let w = Window::from_params(p);
    let step = w.step_s();
    let t = app.traces;
    let win = w.sql();
    let summary = app.rows(&format!(
        "SELECT ServiceName AS service, countIf(SpanKind = 'Server') AS requests, \
         countIf(SpanKind = 'Server' AND StatusCode = 'Error') AS errors, count() AS spans, \
         round(quantileIf(0.5)(Duration, SpanKind = 'Server') / 1e6, 2) AS p50_ms, \
         round(quantileIf(0.95)(Duration, SpanKind = 'Server') / 1e6, 2) AS p95_ms, \
         round(quantileIf(0.99)(Duration, SpanKind = 'Server') / 1e6, 2) AS p99_ms \
         FROM {t} WHERE {win} GROUP BY service ORDER BY requests DESC, service"
    ))?;
    let series = app.rows(&format!(
        "SELECT ServiceName AS service, toUnixTimestamp(toStartOfInterval(Timestamp, INTERVAL {step} SECOND)) * 1000 AS t, \
         countIf(SpanKind = 'Server') AS req, countIf(SpanKind = 'Server' AND StatusCode = 'Error') AS err, \
         round(quantileIf(0.95)(Duration, SpanKind = 'Server') / 1e6, 2) AS p95_ms \
         FROM {t} WHERE {win} GROUP BY service, t ORDER BY service, t"
    ))?;
    let endpoints = app.rows(&format!(
        "SELECT ServiceName AS service, SpanName AS name, count() AS requests, countIf(StatusCode = 'Error') AS errors, \
         round(quantile(0.95)(Duration) / 1e6, 2) AS p95_ms FROM {t} WHERE {win} AND SpanKind = 'Server' \
         GROUP BY service, name ORDER BY service, requests DESC LIMIT 5 BY service"
    ))?;
    Ok(json!({ "from": w.from, "to": w.to, "step_ms": step * 1000, "services": summary, "series": series, "endpoints": endpoints }))
}
