//! OTLP/HTTP JSON (ExportTraceServiceRequest) -> RowBinary for otel_traces.
//! Borrowing deserialisation: strings without escapes are not copied.

use crate::encode::{kind_str, rb_str, status_str, varint};
use serde::Deserialize;
use std::borrow::Cow;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Req<'a> {
    #[serde(borrow, default)]
    resource_spans: Vec<ResourceSpans<'a>>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ResourceSpans<'a> {
    #[serde(borrow, default)]
    resource: Option<Resource<'a>>,
    #[serde(borrow, default)]
    scope_spans: Vec<ScopeSpans<'a>>,
}
#[derive(Deserialize)]
struct Resource<'a> {
    #[serde(borrow, default)]
    attributes: Vec<KeyValue<'a>>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScopeSpans<'a> {
    #[serde(borrow, default)]
    scope: Option<Scope<'a>>,
    #[serde(borrow, default)]
    spans: Vec<Span<'a>>,
}
#[derive(Deserialize)]
struct Scope<'a> {
    #[serde(borrow, default)]
    name: Cow<'a, str>,
    #[serde(borrow, default)]
    version: Cow<'a, str>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Span<'a> {
    #[serde(borrow, default)]
    trace_id: Cow<'a, str>,
    #[serde(borrow, default)]
    span_id: Cow<'a, str>,
    #[serde(borrow, default)]
    parent_span_id: Cow<'a, str>,
    #[serde(borrow, default)]
    trace_state: Cow<'a, str>,
    #[serde(borrow, default)]
    name: Cow<'a, str>,
    #[serde(default)]
    kind: u8,
    #[serde(borrow)]
    start_time_unix_nano: NumOrStr<'a>,
    #[serde(borrow)]
    end_time_unix_nano: NumOrStr<'a>,
    #[serde(borrow, default)]
    attributes: Vec<KeyValue<'a>>,
    #[serde(borrow, default)]
    status: Option<Status<'a>>,
}
#[derive(Deserialize)]
struct Status<'a> {
    #[serde(default)]
    code: u8,
    #[serde(borrow, default)]
    message: Cow<'a, str>,
}
#[derive(Deserialize)]
struct KeyValue<'a> {
    #[serde(borrow)]
    key: Cow<'a, str>,
    #[serde(borrow)]
    value: AnyValue<'a>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnyValue<'a> {
    #[serde(borrow, default)]
    string_value: Option<Cow<'a, str>>,
    #[serde(borrow, default)]
    int_value: Option<NumOrStr<'a>>,
    #[serde(default)]
    double_value: Option<f64>,
    #[serde(default)]
    bool_value: Option<bool>,
}
#[derive(Deserialize)]
#[serde(untagged)]
enum NumOrStr<'a> {
    N(u64),
    S(#[serde(borrow)] Cow<'a, str>),
}
impl NumOrStr<'_> {
    fn as_u64(&self) -> u64 {
        match self {
            NumOrStr::N(n) => *n,
            NumOrStr::S(s) => s.parse().unwrap_or(0),
        }
    }
}

fn rb_any(out: &mut Vec<u8>, v: &AnyValue) {
    if let Some(s) = &v.string_value {
        rb_str(out, s.as_bytes());
    } else if let Some(i) = &v.int_value {
        match i {
            NumOrStr::N(n) => rb_str(out, itoa::Buffer::new().format(*n).as_bytes()),
            NumOrStr::S(s) => rb_str(out, s.as_bytes()),
        }
    } else if let Some(d) = v.double_value {
        rb_str(out, ryu::Buffer::new().format(d).as_bytes());
    } else if let Some(b) = v.bool_value {
        rb_str(out, if b { b"true" } else { b"false" });
    } else {
        out.push(0);
    }
}

fn rb_map(out: &mut Vec<u8>, kvs: &[KeyValue]) {
    varint(out, kvs.len() as u64);
    for kv in kvs {
        rb_str(out, kv.key.as_bytes());
        rb_any(out, &kv.value);
    }
}

/// Decode one request body, append rows to `out`. Returns rows appended.
pub fn decode_traces(body: &[u8], out: &mut Vec<u8>, scratch: &mut Vec<u8>) -> Result<usize, String> {
    let req: Req = serde_json::from_slice(body).map_err(|e| e.to_string())?;
    let mut rows = 0;
    for rs in &req.resource_spans {
        let rattrs: &[KeyValue] = rs.resource.as_ref().map(|r| r.attributes.as_slice()).unwrap_or(&[]);
        let service = rattrs
            .iter()
            .find(|kv| kv.key == "service.name")
            .and_then(|kv| kv.value.string_value.as_deref())
            .unwrap_or("unknown_service");
        // resource map is identical for every span of this resource: encode once
        scratch.clear();
        rb_map(scratch, rattrs);
        for ss in &rs.scope_spans {
            let (sn, sv) = ss.scope.as_ref().map(|s| (s.name.as_ref(), s.version.as_ref())).unwrap_or(("", ""));
            for s in &ss.spans {
                let start = s.start_time_unix_nano.as_u64();
                let end = s.end_time_unix_nano.as_u64();
                out.extend_from_slice(&(start as i64).to_le_bytes());
                rb_str(out, s.trace_id.as_bytes());
                rb_str(out, s.span_id.as_bytes());
                rb_str(out, s.parent_span_id.as_bytes());
                rb_str(out, s.trace_state.as_bytes());
                rb_str(out, s.name.as_bytes());
                rb_str(out, kind_str(s.kind).as_bytes());
                rb_str(out, service.as_bytes());
                out.extend_from_slice(scratch);
                rb_str(out, sn.as_bytes());
                rb_str(out, sv.as_bytes());
                rb_map(out, &s.attributes);
                out.extend_from_slice(&end.saturating_sub(start).to_le_bytes());
                let (code, msg) = s.status.as_ref().map(|st| (st.code, st.message.as_ref())).unwrap_or((0, ""));
                rb_str(out, status_str(code).as_bytes());
                rb_str(out, msg.as_bytes());
                rows += 1;
            }
        }
    }
    Ok(rows)
}
