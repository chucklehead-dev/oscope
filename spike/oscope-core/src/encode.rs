//! Row encoders for ClickStack-shaped tables. Append into reusable buffers.

pub const TRACES_DDL: &str = "CREATE TABLE IF NOT EXISTS otel_traces (
  Timestamp DateTime64(9) CODEC(Delta, ZSTD(1)),
  TraceId String CODEC(ZSTD(1)),
  SpanId String CODEC(ZSTD(1)),
  ParentSpanId String CODEC(ZSTD(1)),
  TraceState String CODEC(ZSTD(1)),
  SpanName LowCardinality(String) CODEC(ZSTD(1)),
  SpanKind LowCardinality(String) CODEC(ZSTD(1)),
  ServiceName LowCardinality(String) CODEC(ZSTD(1)),
  ResourceAttributes Map(LowCardinality(String), String) CODEC(ZSTD(1)),
  ScopeName String CODEC(ZSTD(1)),
  ScopeVersion String CODEC(ZSTD(1)),
  SpanAttributes Map(LowCardinality(String), String) CODEC(ZSTD(1)),
  Duration UInt64 CODEC(ZSTD(1)),
  StatusCode LowCardinality(String) CODEC(ZSTD(1)),
  StatusMessage String CODEC(ZSTD(1)),
  Events Nested (Timestamp DateTime64(9), Name LowCardinality(String), Attributes Map(LowCardinality(String), String)) CODEC(ZSTD(1)),
  Links Nested (TraceId String, SpanId String, TraceState String, Attributes Map(LowCardinality(String), String)) CODEC(ZSTD(1))
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, SpanName, toDateTime(Timestamp))";

pub const TRACES_INSERT: &str = "INSERT INTO otel_traces (Timestamp, TraceId, SpanId, ParentSpanId, TraceState, SpanName, SpanKind, ServiceName, ResourceAttributes, ScopeName, ScopeVersion, SpanAttributes, Duration, StatusCode, StatusMessage)";

pub const LOGS_DDL: &str = "CREATE TABLE IF NOT EXISTS otel_logs (
  Timestamp DateTime64(9) CODEC(Delta(8), ZSTD(1)),
  TraceId String CODEC(ZSTD(1)),
  SpanId String CODEC(ZSTD(1)),
  TraceFlags UInt8,
  SeverityText LowCardinality(String) CODEC(ZSTD(1)),
  SeverityNumber UInt8,
  ServiceName LowCardinality(String) CODEC(ZSTD(1)),
  Body String CODEC(ZSTD(1)),
  ResourceAttributes Map(LowCardinality(String), String) CODEC(ZSTD(1)),
  ScopeName String CODEC(ZSTD(1)),
  LogAttributes Map(LowCardinality(String), String) CODEC(ZSTD(1))
) ENGINE = MergeTree
PARTITION BY toDate(Timestamp)
ORDER BY (ServiceName, toDateTime(Timestamp))";

pub const LOGS_INSERT: &str = "INSERT INTO otel_logs (Timestamp, TraceId, SpanId, TraceFlags, SeverityText, SeverityNumber, ServiceName, Body, ResourceAttributes, ScopeName, LogAttributes)";

/// Spike-only storage experiment: OSCOPE_CODEC=lz4 strips the explicit
/// ZSTD codecs so every column uses ClickHouse's default (LZ4).
pub fn ddl(base: &str) -> String {
    if std::env::var("OSCOPE_CODEC").as_deref() != Ok("lz4") {
        return base.to_string();
    }
    let mut out = String::with_capacity(base.len());
    let mut rest = base;
    while let Some(i) = rest.find(" CODEC(") {
        out.push_str(&rest[..i]);
        let mut depth = 0;
        let mut end = i + 1;
        for (j, c) in rest[i + 1..].char_indices() {
            match c {
                '(' => depth += 1,
                ')' => {
                    depth -= 1;
                    if depth == 0 {
                        end = i + 1 + j + 1;
                        break;
                    }
                }
                _ => {}
            }
        }
        rest = &rest[end..];
    }
    out.push_str(rest);
    out
}

pub fn kind_str(k: u8) -> &'static str {
    match k {
        1 => "Internal",
        2 => "Server",
        3 => "Client",
        4 => "Producer",
        5 => "Consumer",
        _ => "Unspecified",
    }
}
pub fn status_str(s: u8) -> &'static str {
    match s {
        1 => "Ok",
        2 => "Error",
        _ => "Unset",
    }
}
pub fn severity_str(s: u8) -> &'static str {
    match s {
        1..=4 => "TRACE",
        5..=8 => "DEBUG",
        9..=12 => "INFO",
        13..=16 => "WARN",
        17..=20 => "ERROR",
        21..=24 => "FATAL",
        _ => "",
    }
}

/// Borrowed view of one finished span. Attribute values are already strings
/// (ClickStack stores Map(String,String)).
pub struct SpanRow<'a> {
    pub start_ns: u64,
    pub end_ns: u64,
    pub trace_id: &'a [u8; 16],
    pub span_id: u64,
    pub parent_span_id: u64,
    pub name: &'a str,
    pub kind: u8,
    pub status: u8,
    pub attrs: &'a mut dyn Iterator<Item = (&'a str, &'a [u8])>,
}

pub struct Resource {
    pub service_name: String,
    pub scope_name: String,
    pub scope_version: String,
    pub attrs: Vec<(String, String)>,
}

const HEX: &[u8; 16] = b"0123456789abcdef";

#[inline]
fn hex_into(dst: &mut [u8], src: &[u8]) {
    for (i, b) in src.iter().enumerate() {
        dst[2 * i] = HEX[(b >> 4) as usize];
        dst[2 * i + 1] = HEX[(b & 15) as usize];
    }
}

// ---------------------------------------------------------------- RowBinary

#[inline]
pub fn varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}
#[inline]
pub fn rb_str(out: &mut Vec<u8>, s: &[u8]) {
    varint(out, s.len() as u64);
    out.extend_from_slice(s);
}
#[inline]
fn rb_hex(out: &mut Vec<u8>, src: &[u8]) {
    let mut tmp = [0u8; 32];
    let n = src.len() * 2;
    hex_into(&mut tmp[..n], src);
    rb_str(out, &tmp[..n]);
}
#[inline]
fn rb_span_id(out: &mut Vec<u8>, id: u64) {
    if id == 0 {
        out.push(0);
    } else {
        rb_hex(out, &id.to_be_bytes());
    }
}

pub struct RowBinaryTraces {
    pub buf: Vec<u8>,
    pub rows: usize,
    resource_map: Vec<u8>,
    service: Vec<u8>,
    scope: Vec<u8>,
    attr_count_pos: Vec<u8>,
}

impl RowBinaryTraces {
    pub fn new(r: &Resource) -> Self {
        let mut resource_map = Vec::new();
        varint(&mut resource_map, r.attrs.len() as u64);
        for (k, v) in &r.attrs {
            rb_str(&mut resource_map, k.as_bytes());
            rb_str(&mut resource_map, v.as_bytes());
        }
        let mut service = Vec::new();
        rb_str(&mut service, r.service_name.as_bytes());
        let mut scope = Vec::new();
        rb_str(&mut scope, r.scope_name.as_bytes());
        rb_str(&mut scope, r.scope_version.as_bytes());
        RowBinaryTraces { buf: Vec::with_capacity(1 << 20), rows: 0, resource_map, service, scope, attr_count_pos: Vec::new() }
    }

    pub fn clear(&mut self) {
        self.buf.clear();
        self.rows = 0;
    }

    /// `n_attrs` must equal the number of items the iterator yields.
    #[inline]
    pub fn push(&mut self, row: SpanRow, n_attrs: usize) {
        let o = &mut self.buf;
        o.extend_from_slice(&(row.start_ns as i64).to_le_bytes());
        rb_hex(o, row.trace_id);
        rb_span_id(o, row.span_id);
        rb_span_id(o, row.parent_span_id);
        o.push(0); // TraceState
        rb_str(o, row.name.as_bytes());
        rb_str(o, kind_str(row.kind).as_bytes());
        o.extend_from_slice(&self.service);
        o.extend_from_slice(&self.resource_map);
        o.extend_from_slice(&self.scope);
        varint(o, n_attrs as u64);
        for (k, v) in row.attrs {
            rb_str(o, k.as_bytes());
            rb_str(o, v);
        }
        o.extend_from_slice(&row.end_ns.saturating_sub(row.start_ns).to_le_bytes());
        rb_str(o, status_str(row.status).as_bytes());
        o.push(0); // StatusMessage
        self.rows += 1;
        let _ = &self.attr_count_pos;
    }
}

pub struct LogRow<'a> {
    pub ts_ns: u64,
    pub trace_id: &'a [u8; 16],
    pub span_id: u64,
    pub severity: u8,
    pub body: &'a [u8],
    pub attrs: &'a mut dyn Iterator<Item = (&'a str, &'a [u8])>,
}

pub struct RowBinaryLogs {
    pub buf: Vec<u8>,
    pub rows: usize,
    resource_map: Vec<u8>,
    service: Vec<u8>,
    scope_name: Vec<u8>,
}

impl RowBinaryLogs {
    pub fn new(r: &Resource) -> Self {
        let t = RowBinaryTraces::new(r);
        let mut scope_name = Vec::new();
        rb_str(&mut scope_name, r.scope_name.as_bytes());
        RowBinaryLogs { buf: Vec::with_capacity(1 << 20), rows: 0, resource_map: t.resource_map, service: t.service, scope_name }
    }
    pub fn clear(&mut self) {
        self.buf.clear();
        self.rows = 0;
    }
    #[inline]
    pub fn push(&mut self, row: LogRow, n_attrs: usize) {
        let o = &mut self.buf;
        o.extend_from_slice(&(row.ts_ns as i64).to_le_bytes());
        if row.trace_id == &[0; 16] {
            o.push(0);
        } else {
            rb_hex(o, row.trace_id);
        }
        rb_span_id(o, row.span_id);
        o.push(if row.span_id != 0 { 1 } else { 0 });
        rb_str(o, severity_str(row.severity).as_bytes());
        o.push(row.severity);
        o.extend_from_slice(&self.service);
        rb_str(o, row.body);
        o.extend_from_slice(&self.resource_map);
        o.extend_from_slice(&self.scope_name);
        varint(o, n_attrs as u64);
        for (k, v) in row.attrs {
            rb_str(o, k.as_bytes());
            rb_str(o, v);
        }
        self.rows += 1;
    }
}

// ---------------------------------------------------------------- JSONEachRow
// The comparison baseline: what the Jolt exporter produces, done in Rust.

#[inline]
fn json_str(o: &mut Vec<u8>, s: &[u8]) {
    o.push(b'"');
    let mut start = 0;
    for (i, &b) in s.iter().enumerate() {
        let esc: &[u8] = match b {
            b'"' => b"\\\"",
            b'\\' => b"\\\\",
            b'\n' => b"\\n",
            b'\r' => b"\\r",
            b'\t' => b"\\t",
            0..=0x1f => {
                o.extend_from_slice(&s[start..i]);
                o.extend_from_slice(format!("\\u{:04x}", b).as_bytes());
                start = i + 1;
                continue;
            }
            _ => continue,
        };
        o.extend_from_slice(&s[start..i]);
        o.extend_from_slice(esc);
        start = i + 1;
    }
    o.extend_from_slice(&s[start..]);
    o.push(b'"');
}

pub struct JsonTraces {
    pub buf: Vec<u8>,
    pub rows: usize,
    fixed: Vec<u8>,
}

impl JsonTraces {
    pub fn new(r: &Resource) -> Self {
        let mut fixed = Vec::new();
        fixed.extend_from_slice(b",\"ServiceName\":");
        json_str(&mut fixed, r.service_name.as_bytes());
        fixed.extend_from_slice(b",\"ResourceAttributes\":{");
        for (i, (k, v)) in r.attrs.iter().enumerate() {
            if i > 0 {
                fixed.push(b',');
            }
            json_str(&mut fixed, k.as_bytes());
            fixed.push(b':');
            json_str(&mut fixed, v.as_bytes());
        }
        fixed.extend_from_slice(b"},\"ScopeName\":");
        json_str(&mut fixed, r.scope_name.as_bytes());
        fixed.extend_from_slice(b",\"ScopeVersion\":");
        json_str(&mut fixed, r.scope_version.as_bytes());
        JsonTraces { buf: Vec::with_capacity(1 << 20), rows: 0, fixed }
    }
    pub fn clear(&mut self) {
        self.buf.clear();
        self.rows = 0;
    }
    pub fn push(&mut self, row: SpanRow) {
        let o = &mut self.buf;
        let mut ib = itoa::Buffer::new();
        let mut hex = [0u8; 32];
        o.extend_from_slice(b"{\"Timestamp\":\"");
        o.extend_from_slice(ib.format(row.start_ns / 1_000_000_000).as_bytes());
        o.push(b'.');
        let frac = row.start_ns % 1_000_000_000;
        let fs = ib.format(frac);
        for _ in fs.len()..9 {
            o.push(b'0');
        }
        o.extend_from_slice(fs.as_bytes());
        o.extend_from_slice(b"\",\"TraceId\":\"");
        hex_into(&mut hex, row.trace_id);
        o.extend_from_slice(&hex);
        o.extend_from_slice(b"\",\"SpanId\":\"");
        hex_into(&mut hex[..16], &row.span_id.to_be_bytes());
        o.extend_from_slice(&hex[..16]);
        o.extend_from_slice(b"\",\"ParentSpanId\":\"");
        if row.parent_span_id != 0 {
            hex_into(&mut hex[..16], &row.parent_span_id.to_be_bytes());
            o.extend_from_slice(&hex[..16]);
        }
        o.extend_from_slice(b"\",\"TraceState\":\"\",\"SpanName\":");
        json_str(o, row.name.as_bytes());
        o.extend_from_slice(b",\"SpanKind\":\"");
        o.extend_from_slice(kind_str(row.kind).as_bytes());
        o.push(b'"');
        o.extend_from_slice(&self.fixed);
        o.extend_from_slice(b",\"SpanAttributes\":{");
        for (i, (k, v)) in row.attrs.enumerate() {
            if i > 0 {
                o.push(b',');
            }
            json_str(o, k.as_bytes());
            o.push(b':');
            json_str(o, v);
        }
        o.extend_from_slice(b"},\"Duration\":");
        o.extend_from_slice(ib.format(row.end_ns.saturating_sub(row.start_ns)).as_bytes());
        o.extend_from_slice(b",\"StatusCode\":\"");
        o.extend_from_slice(status_str(row.status).as_bytes());
        o.extend_from_slice(b"\",\"StatusMessage\":\"\"}\n");
        self.rows += 1;
    }
}
