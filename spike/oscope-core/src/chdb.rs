//! Minimal libchdb 26.7 binding: connection, query, streaming insert.

use std::ffi::{c_char, c_void, CStr, CString};

#[repr(C)]
pub struct ConnHandle {
    _internal: *mut c_void,
}
type ChdbConnection = *mut ConnHandle;
#[repr(C)]
pub struct ChdbResult {
    _internal: *mut c_void,
}
type InsertStream = *mut c_void;

extern "C" {
    fn chdb_connect(argc: i32, argv: *mut *mut c_char) -> *mut ChdbConnection;
    fn chdb_close_conn(conn: *mut ChdbConnection);
    fn chdb_query_n(conn: ChdbConnection, q: *const c_char, ql: usize, f: *const c_char, fl: usize) -> *mut ChdbResult;
    fn chdb_result_buffer(r: *mut ChdbResult) -> *mut c_char;
    fn chdb_result_length(r: *mut ChdbResult) -> usize;
    fn chdb_result_error(r: *mut ChdbResult) -> *const c_char;
    fn chdb_result_rows_written(r: *mut ChdbResult) -> u64;
    fn chdb_destroy_query_result(r: *mut ChdbResult);
    fn chdb_stream_insert_n(conn: ChdbConnection, q: *const c_char, ql: usize, f: *const c_char, fl: usize) -> InsertStream;
    fn chdb_stream_append(s: InsertStream, data: *const c_void, len: usize) -> i32;
    fn chdb_stream_done(s: InsertStream) -> *mut ChdbResult;
    fn chdb_stream_insert_error(s: InsertStream) -> *const c_char;
    fn chdb_destroy_insert_stream(s: InsertStream);
    fn chdb_set_signal_handlers_enabled(enabled: i32);
}

/// Must be called before the first connection. Hosts with their own signal
/// handling (Go, JVM, game loops) turn the engine's handlers off.
pub fn set_signal_handlers_enabled(on: bool) {
    unsafe { chdb_set_signal_handlers_enabled(on as i32) }
}

pub struct Conn {
    h: *mut ChdbConnection,
}
unsafe impl Send for Conn {}

fn cstr_err(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        let s = unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned();
        if s.is_empty() { None } else { Some(s) }
    }
}

impl Conn {
    pub fn open(path: Option<&str>) -> Result<Conn, String> {
        let mut args: Vec<CString> = vec![CString::new("chdb").unwrap()];
        if let Some(p) = path {
            args.push(CString::new(format!("--path={p}")).unwrap());
        }
        let mut ptrs: Vec<*mut c_char> = args.iter().map(|a| a.as_ptr() as *mut c_char).collect();
        let h = unsafe { chdb_connect(ptrs.len() as i32, ptrs.as_mut_ptr()) };
        if h.is_null() { Err("chdb_connect failed".into()) } else { Ok(Conn { h }) }
    }

    fn finish(r: *mut ChdbResult) -> Result<(Vec<u8>, u64), String> {
        if r.is_null() {
            return Err("null result".into());
        }
        unsafe {
            if let Some(e) = cstr_err(chdb_result_error(r)) {
                chdb_destroy_query_result(r);
                return Err(e);
            }
            let len = chdb_result_length(r);
            let buf = chdb_result_buffer(r);
            let out = if len > 0 && !buf.is_null() { std::slice::from_raw_parts(buf as *const u8, len).to_vec() } else { Vec::new() };
            let rows = chdb_result_rows_written(r);
            chdb_destroy_query_result(r);
            Ok((out, rows))
        }
    }

    /// Binary-safe query. Returns (output bytes, rows written).
    pub fn query(&self, sql: &[u8], format: &str) -> Result<(Vec<u8>, u64), String> {
        let r = unsafe { chdb_query_n(*self.h, sql.as_ptr() as _, sql.len(), format.as_ptr() as _, format.len()) };
        Self::finish(r)
    }

    pub fn exec(&self, sql: &str) -> Result<(), String> {
        self.query(sql.as_bytes(), "TabSeparated").map(|_| ())
    }

    pub fn query_string(&self, sql: &str, format: &str) -> Result<String, String> {
        self.query(sql.as_bytes(), format).map(|(b, _)| String::from_utf8_lossy(&b).into_owned())
    }

    /// Streaming insert: `insert` is an INSERT without FORMAT; chunks are raw format bytes.
    pub fn stream_insert(&self, insert: &str, format: &str, chunks: &[&[u8]]) -> Result<u64, String> {
        unsafe {
            let s = chdb_stream_insert_n(*self.h, insert.as_ptr() as _, insert.len(), format.as_ptr() as _, format.len());
            if let Some(e) = cstr_err(chdb_stream_insert_error(s)) {
                chdb_destroy_insert_stream(s);
                return Err(e);
            }
            for c in chunks {
                if chdb_stream_append(s, c.as_ptr() as _, c.len()) != 0 {
                    let e = cstr_err(chdb_stream_insert_error(s)).unwrap_or_else(|| "append failed".into());
                    chdb_destroy_insert_stream(s);
                    return Err(e);
                }
            }
            let r = chdb_stream_done(s);
            let res = Self::finish(r);
            chdb_destroy_insert_stream(s);
            res.map(|(_, rows)| rows)
        }
    }
}

impl Drop for Conn {
    fn drop(&mut self) {
        unsafe { chdb_close_conn(self.h) }
    }
}
