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

// (ptr, len) variants of every entry point that takes osc_str by value, for
// FFIs that cannot pass structs by value.

#[no_mangle]
pub unsafe extern "C" fn osc_intern_n(ptr: *const u8, len: usize) -> u32 {
    osc_intern(osc_str { ptr, len })
}

#[no_mangle]
pub unsafe extern "C" fn osc_span_attr_str_n(span: u64, key: u32, ptr: *const u8, len: usize) {
    osc_span_attr_str(span, key, osc_str { ptr, len })
}

// NUL-terminated variants: hosts whose FFI converts strings natively (Chez,
// and so Jolt, via :string) pay less than building (ptr, len) by hand.

#[no_mangle]
pub unsafe extern "C" fn osc_intern_cstr(s: *const c_char) -> u32 {
    let b = if s.is_null() { &[][..] } else { CStr::from_ptr(s).to_bytes() };
    recorder::intern(b)
}

#[no_mangle]
pub unsafe extern "C" fn osc_span_attr_cstr(span: u64, key: u32, s: *const c_char) {
    let b = if s.is_null() { &[][..] } else { CStr::from_ptr(s).to_bytes() };
    recorder::span_attr(span, key, AttrVal::Str(b))
}

unsafe fn cstr_bytes<'a>(s: *const c_char) -> &'a [u8] {
    if s.is_null() { &[] } else { CStr::from_ptr(s).to_bytes() }
}

/// osc_start without a struct: "" means unset for the string arguments and 0
/// means default for the numbers.
#[no_mangle]
pub unsafe extern "C" fn osc_start_cstr(db_path: *const c_char, wal_path: *const c_char, wal_fsync: u8, service_name: *const c_char, batch_rows: u32, flush_interval_ms: u32, ring_bytes: u32) -> i32 {
    let none_if_empty = |p: *const c_char| if p.is_null() || *p == 0 { std::ptr::null() } else { p };
    osc_start(&osc_config {
        db_path: none_if_empty(db_path),
        wal_path: none_if_empty(wal_path),
        wal_fsync,
        service_name: none_if_empty(service_name),
        batch_rows,
        flush_interval_ms,
        ring_bytes,
    })
}

/// A log correlated with this thread's innermost open span, with up to three
/// string attributes (key 0 = absent). For FFIs without struct arrays.
#[no_mangle]
pub unsafe extern "C" fn osc_log_cstr3(severity: u8, body: *const c_char, k1: u32, v1: *const c_char, k2: u32, v2: *const c_char, k3: u32, v3: *const c_char) -> i32 {
    let mut attrs: [(u32, AttrVal); 3] = [(0, AttrVal::Bool(false)); 3];
    let mut n = 0;
    for (k, v) in [(k1, v1), (k2, v2), (k3, v3)] {
        if k != 0 {
            attrs[n] = (k, AttrVal::Str(cstr_bytes(v)));
            n += 1;
        }
    }
    if recorder::log(severity, cstr_bytes(body), &attrs[..n]) { 0 } else { -1 }
}

/// Run a read query; answers a NUL-terminated result (free with
/// osc_free_text) or NULL on error.
#[no_mangle]
pub unsafe extern "C" fn osc_query_text(sql: *const c_char, format: *const c_char) -> *mut c_char {
    let b = cstr_bytes(sql);
    let mut out: *mut u8 = std::ptr::null_mut();
    let mut len = 0usize;
    if osc_query(osc_str { ptr: b.as_ptr(), len: b.len() }, format, &mut out, &mut len) != 0 {
        return std::ptr::null_mut();
    }
    let v = std::slice::from_raw_parts(out, len).to_vec();
    osc_free(out, len);
    std::ffi::CString::new(v.into_iter().filter(|&c| c != 0).collect::<Vec<u8>>()).unwrap().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn osc_free_text(p: *mut c_char) {
    if !p.is_null() {
        drop(std::ffi::CString::from_raw(p));
    }
}

#[no_mangle]
pub unsafe extern "C" fn osc_query_n(sql: *const u8, sql_len: usize, format: *const c_char, out: *mut *mut u8, out_len: *mut usize) -> i32 {
    osc_query(osc_str { ptr: sql, len: sql_len }, format, out, out_len)
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

/// End the span as Error if it is still open on this thread (see recorder::span_abort).
#[no_mangle]
pub extern "C" fn osc_span_abort(span: u64) {
    recorder::span_abort(span)
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

/// Bulk path: `buf` holds one or more frames `u32 len (LE) | record`, where each
/// record is a K_FULL span or K_LOG log in the ring wire format (see
/// include/oscope.h). One FFI crossing per batch, no pointers inside the
/// buffer. Returns the number of records accepted (the rest were dropped for
/// lack of ring space), or -1 if the buffer is malformed (nothing accepted).
#[no_mangle]
pub unsafe extern "C" fn osc_submit(buf: *const u8, len: usize) -> i64 {
    if buf.is_null() {
        return if len == 0 { 0 } else { -1 };
    }
    let b = std::slice::from_raw_parts(buf, len);
    let mut p = 0;
    while p < b.len() {
        let Some(h) = b.get(p..p + 4) else { return -1 };
        let n = u32::from_le_bytes(h.try_into().unwrap()) as usize;
        let Some(rec) = b.get(p + 4..p + 4 + n) else { return -1 };
        if !crate::assemble::validate_submitted(rec) {
            return -1;
        }
        p += 4 + n;
    }
    let mut accepted = 0;
    p = 0;
    while p < b.len() {
        let n = u32::from_le_bytes(b[p..p + 4].try_into().unwrap()) as usize;
        if recorder::submit_raw(&b[p + 4..p + 4 + n]) {
            accepted += 1;
        }
        p += 4 + n;
    }
    accepted
}

/// Call before osc_start in hosts that own signal handling (Go, JVM).
#[no_mangle]
pub extern "C" fn osc_set_engine_signal_handlers(enabled: u8) {
    crate::chdb::set_signal_handlers_enabled(enabled != 0)
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
