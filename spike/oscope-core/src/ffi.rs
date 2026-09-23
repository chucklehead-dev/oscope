//! C ABI. Plain structs, integer handles, borrowed (ptr,len) strings, no
//! callbacks, no ownership transfer on the hot path.

use crate::encode::Resource;
use crate::pipeline::{Config, InsertMode, Pipeline};
use crate::recorder::{self, AttrVal, SpanRec};
use std::ffi::{c_char, CStr};
use std::sync::Mutex;
use std::time::Duration;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct osc_str {
    pub ptr: *const u8,
    pub len: usize,
}
impl osc_str {
    #[inline]
    unsafe fn bytes<'a>(self) -> &'a [u8] {
        if self.ptr.is_null() { &[] } else { std::slice::from_raw_parts(self.ptr, self.len) }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union osc_value {
    pub i: i64,
    pub f: f64,
    pub b: u8,
    pub s: osc_str,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct osc_attr {
    pub key: u32,
    pub tag: u8, // 1 str, 2 i64, 3 f64, 4 bool
    pub v: osc_value,
}

#[repr(C)]
pub struct osc_span_rec {
    pub trace_id: [u8; 16],
    pub span_id: u64,
    pub parent_span_id: u64,
    pub name: u32,
    pub kind: u8,
    pub status: u8,
    pub start_ns: u64,
    pub end_ns: u64,
    pub attrs: *const osc_attr,
    pub n_attrs: u32,
}

#[repr(C)]
pub struct osc_config {
    pub db_path: *const c_char,   // NULL = in-memory
    pub wal_path: *const c_char,  // NULL = no WAL (ephemeral)
    pub wal_fsync: u8,
    pub service_name: *const c_char,
    pub batch_rows: u32,
    pub flush_interval_ms: u32,
    pub ring_bytes: u32,          // per thread, power of two; 0 = 1 MiB
}

static PIPE: Mutex<Option<Pipeline>> = Mutex::new(None);

unsafe fn opt_str(p: *const c_char) -> Option<String> {
    if p.is_null() { None } else { Some(CStr::from_ptr(p).to_string_lossy().into_owned()) }
}

#[inline]
unsafe fn attr_val<'a>(a: &osc_attr) -> AttrVal<'a> {
    match a.tag {
        1 => AttrVal::Str(a.v.s.bytes()),
        2 => AttrVal::I64(a.v.i),
        3 => AttrVal::F64(a.v.f),
        _ => AttrVal::Bool(a.v.b != 0),
    }
}

/// 0 on success.
#[no_mangle]
pub unsafe extern "C" fn osc_start(cfg: *const osc_config) -> i32 {
    let c = &*cfg;
    if c.ring_bytes != 0 {
        recorder::global().ring_bytes.store((c.ring_bytes as usize).next_power_of_two(), std::sync::atomic::Ordering::Relaxed);
    }
    let cfg = Config {
        path: opt_str(c.db_path),
        wal: opt_str(c.wal_path).map(|p| (p, c.wal_fsync != 0)),
        batch_rows: if c.batch_rows == 0 { 8192 } else { c.batch_rows as usize },
        flush_interval: Duration::from_millis(if c.flush_interval_ms == 0 { 1000 } else { c.flush_interval_ms as u64 }),
        mode: InsertMode::StreamRowBinary,
        resource: Resource {
            service_name: opt_str(c.service_name).unwrap_or_else(|| "unknown_service".into()),
            scope_name: "oscope".into(),
            scope_version: "0.0.1".into(),
            attrs: vec![("telemetry.sdk.name".into(), "oscope-core".into())],
        },
    };
    match Pipeline::start(cfg) {
        Ok(p) => {
            *PIPE.lock().unwrap() = Some(p);
            0
        }
        Err(e) => {
            eprintln!("osc_start: {e}");
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn osc_intern(s: osc_str) -> u32 {
    recorder::intern(s.bytes())
}

#[no_mangle]
pub extern "C" fn osc_now_ns() -> u64 {
    recorder::now_ns()
}

#[no_mangle]
pub extern "C" fn osc_span_start(name: u32, kind: u8) -> u64 {
    recorder::span_start(name, kind)
}

#[no_mangle]
pub unsafe extern "C" fn osc_span_attr_str(span: u64, key: u32, v: osc_str) {
    recorder::span_attr(span, key, AttrVal::Str(v.bytes()))
}
#[no_mangle]
pub extern "C" fn osc_span_attr_i64(span: u64, key: u32, v: i64) {
    recorder::span_attr(span, key, AttrVal::I64(v))
}
#[no_mangle]
pub extern "C" fn osc_span_attr_f64(span: u64, key: u32, v: f64) {
    recorder::span_attr(span, key, AttrVal::F64(v))
}
#[no_mangle]
pub extern "C" fn osc_span_attr_bool(span: u64, key: u32, v: u8) {
    recorder::span_attr(span, key, AttrVal::Bool(v != 0))
}
#[no_mangle]
pub extern "C" fn osc_span_end(span: u64, status: u8) {
    recorder::span_end(span, status)
}

/// One crossing per finished span. For hosts where FFI calls are expensive.
/// Up to 32 attributes are passed without allocation.
#[no_mangle]
pub unsafe extern "C" fn osc_span_record(r: *const osc_span_rec) -> i32 {
    let r = &*r;
    let raw = if r.n_attrs == 0 { &[][..] } else { std::slice::from_raw_parts(r.attrs, r.n_attrs as usize) };
    let mut buf: [(u32, AttrVal); 32] = [(0, AttrVal::Bool(false)); 32];
    let n = raw.len().min(32);
    for (i, a) in raw[..n].iter().enumerate() {
        buf[i] = (a.key, attr_val(a));
    }
    let ok = recorder::record_span(&SpanRec {
        trace_id: r.trace_id,
        span_id: r.span_id,
        parent_span_id: r.parent_span_id,
        name: r.name,
        kind: r.kind,
        status: r.status,
        start_ns: r.start_ns,
        end_ns: r.end_ns,
        attrs: &buf[..n],
    });
    if ok { 0 } else { -1 }
}

#[no_mangle]
pub unsafe extern "C" fn osc_log(severity: u8, body: osc_str, attrs: *const osc_attr, n_attrs: u32) -> i32 {
    let raw = if n_attrs == 0 { &[][..] } else { std::slice::from_raw_parts(attrs, n_attrs as usize) };
    let mut buf: [(u32, AttrVal); 32] = [(0, AttrVal::Bool(false)); 32];
    let n = raw.len().min(32);
    for (i, a) in raw[..n].iter().enumerate() {
        buf[i] = (a.key, attr_val(a));
    }
    if recorder::log(severity, body.bytes(), &buf[..n]) { 0 } else { -1 }
}

/// 0 when everything recorded before the call is committed.
#[no_mangle]
pub extern "C" fn osc_flush(timeout_ms: u32) -> i32 {
    match PIPE.lock().unwrap().as_ref() {
        Some(p) if p.flush(Duration::from_millis(timeout_ms as u64)) => 0,
        _ => -1,
    }
}

#[no_mangle]
pub extern "C" fn osc_dropped() -> u64 {
    recorder::dropped_total()
}

/// Run a read query. On success *out/*out_len hold a malloc'd-by-Rust buffer
/// that must be released with osc_free.
#[no_mangle]
pub unsafe extern "C" fn osc_query(sql: osc_str, format: *const c_char, out: *mut *mut u8, out_len: *mut usize) -> i32 {
    let g = PIPE.lock().unwrap();
    let Some(p) = g.as_ref() else { return -1 };
    let fmt = opt_str(format).unwrap_or_else(|| "JSONEachRow".into());
    let res = p.query(&String::from_utf8_lossy(sql.bytes()), &fmt);
    let bytes = match res {
        Ok(s) => s.into_bytes(),
        Err(e) => {
            eprintln!("osc_query: {e}");
            return -1;
        }
    };
    let mut b = bytes.into_boxed_slice();
    *out_len = b.len();
    *out = b.as_mut_ptr();
    std::mem::forget(b);
    0
}

#[no_mangle]
pub unsafe extern "C" fn osc_free(p: *mut u8, len: usize) {
    if !p.is_null() {
        drop(Box::from_raw(std::slice::from_raw_parts_mut(p, len)));
    }
}

#[no_mangle]
pub extern "C" fn osc_stop() -> i32 {
    match PIPE.lock().unwrap().take() {
        Some(p) => {
            p.stop();
            0
        }
        None => -1,
    }
}
