//! Drain side: decode ring records, rebuild spans, encode rows.
//! Runs on the collector thread. Span state is pooled, so steady-state
//! draining does not allocate either.

use crate::encode::{LogRow, Resource, RowBinaryLogs, RowBinaryTraces, SpanRow};
use crate::recorder::{self, *};
use crate::ring::Reader;
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Default)]
struct OpenSpan {
    trace: [u8; 16],
    parent: u64,
    name: u32,
    kind: u8,
    start: u64,
    /// (key id, offset, len) into `vals`
    attrs: Vec<(u32, u32, u32)>,
    vals: Vec<u8>,
}

/// A key id the interner never issued (a host bug) must not take the process
/// down: record it under "?" instead of indexing past the table.
#[inline]
fn key_name(names: &[Arc<str>], k: u32) -> &str {
    names.get(k as usize).map(|a| a.as_ref()).unwrap_or("?")
}

pub struct Assembler {
    open: HashMap<u64, OpenSpan>,
    pool: Vec<OpenSpan>,
    /// END seen before START (span ended on a different thread than it began).
    early_end: HashMap<u64, (u64, u8)>,
    names: Vec<Arc<str>>,
    pub traces: RowBinaryTraces,
    pub logs: RowBinaryLogs,
    tmp_attrs: Vec<(u32, u32, u32)>,
    tmp_vals: Vec<u8>,
}

#[inline]
fn stringify(r: &mut Reader, out: &mut Vec<u8>) -> u32 {
    let tag = r.u8();
    let key = r.u32();
    match tag {
        A_STR => {
            let n = r.u32() as usize;
            out.extend_from_slice(r.bytes(n));
        }
        A_I64 => out.extend_from_slice(itoa::Buffer::new().format(r.u64() as i64).as_bytes()),
        A_F64 => out.extend_from_slice(ryu::Buffer::new().format(f64::from_bits(r.u64())).as_bytes()),
        _ => out.extend_from_slice(if r.u8() != 0 { b"true" } else { b"false" }),
    }
    key
}

impl Assembler {
    pub fn new(res: &Resource) -> Self {
        Assembler {
            open: HashMap::with_capacity(4096),
            pool: Vec::new(),
            early_end: HashMap::new(),
            names: Vec::new(),
            traces: RowBinaryTraces::new(res),
            logs: RowBinaryLogs::new(res),
            tmp_attrs: Vec::new(),
            tmp_vals: Vec::new(),
        }
    }

    fn name(&mut self, id: u32) -> Arc<str> {
        if id as usize >= self.names.len() {
            self.names = recorder::global().interner.read().unwrap().strings.clone();
        }
        self.names.get(id as usize).cloned().unwrap_or_else(|| Arc::from("?"))
    }

    fn emit(&mut self, span_id: u64, s: &OpenSpan, end: u64, status: u8) {
        let name = self.name(s.name);
        // resolve keys up front (may refresh the name cache)
        let max_key = s.attrs.iter().map(|a| a.0).max().unwrap_or(0);
        if max_key as usize >= self.names.len() {
            self.name(max_key);
        }
        let names = &self.names;
        let mut it = s.attrs.iter().map(|&(k, off, len)| {
            (key_name(names, k), &s.vals[off as usize..(off + len) as usize])
        });
        self.traces.push(
            SpanRow {
                start_ns: s.start,
                end_ns: end,
                trace_id: &s.trace,
                span_id,
                parent_span_id: s.parent,
                name: &name,
                kind: s.kind,
                status,
                attrs: &mut it,
            },
            s.attrs.len(),
        );
    }

    fn recycle(&mut self, mut s: OpenSpan) {
        s.attrs.clear();
        s.vals.clear();
        self.pool.push(s);
    }

    pub fn feed(&mut self, rec: &[u8]) {
        let mut r = Reader { b: rec, p: 0 };
        match r.u8() {
            K_START => {
                let mut s = self.pool.pop().unwrap_or_default();
                s.trace.copy_from_slice(r.bytes(16));
                let span = r.u64();
                s.parent = r.u64();
                s.name = r.u32();
                s.kind = r.u8();
                s.start = r.u64();
                if let Some((end, st)) = self.early_end.remove(&span) {
                    self.emit(span, &s, end, st);
                    self.recycle(s);
                } else {
                    self.open.insert(span, s);
                }
            }
            K_ATTR => {
                let span = r.u64();
                if let Some(s) = self.open.get_mut(&span) {
                    let off = s.vals.len() as u32;
                    let key = stringify(&mut r, &mut s.vals);
                    s.attrs.push((key, off, s.vals.len() as u32 - off));
                }
            }
            K_END => {
                let span = r.u64();
                let end = r.u64();
                let st = r.u8();
                match self.open.remove(&span) {
                    Some(s) => {
                        self.emit(span, &s, end, st);
                        self.recycle(s);
                    }
                    None => {
                        self.early_end.insert(span, (end, st));
                    }
                }
            }
            K_FULL => {
                let mut s = self.pool.pop().unwrap_or_default();
                s.trace.copy_from_slice(r.bytes(16));
                let span = r.u64();
                s.parent = r.u64();
                s.name = r.u32();
                s.kind = r.u8();
                let st = r.u8();
                s.start = r.u64();
                let end = r.u64();
                let n = r.u32();
                for _ in 0..n {
                    let off = s.vals.len() as u32;
                    let key = stringify(&mut r, &mut s.vals);
                    s.attrs.push((key, off, s.vals.len() as u32 - off));
                }
                self.emit(span, &s, end, st);
                self.recycle(s);
            }
            K_LOG => {
                let ts = r.u64();
                let mut trace = [0u8; 16];
                trace.copy_from_slice(r.bytes(16));
                let span = r.u64();
                let sev = r.u8();
                let blen = r.u32() as usize;
                let body = r.bytes(blen);
                let n = r.u32();
                self.tmp_attrs.clear();
                self.tmp_vals.clear();
                for _ in 0..n {
                    let off = self.tmp_vals.len() as u32;
                    let key = stringify(&mut r, &mut self.tmp_vals);
                    self.tmp_attrs.push((key, off, self.tmp_vals.len() as u32 - off));
                }
                let max_key = self.tmp_attrs.iter().map(|a| a.0).max().unwrap_or(0);
                if max_key as usize >= self.names.len() {
                    self.name(max_key);
                }
                let names = &self.names;
                let vals = &self.tmp_vals;
                let mut it = self.tmp_attrs.iter().map(|&(k, off, len)| {
                    (key_name(names, k), &vals[off as usize..(off + len) as usize])
                });
                self.logs.push(
                    LogRow { ts_ns: ts, trace_id: &trace, span_id: span, severity: sev, body, attrs: &mut it },
                    self.tmp_attrs.len(),
                );
            }
            k => panic!("unknown record kind {k}"),
        }
    }

    pub fn open_spans(&self) -> usize {
        self.open.len()
    }
}

/// Bounds-checked walk of a host-encoded record. Only K_FULL and K_LOG may be
/// submitted from outside; anything malformed is rejected before it reaches
/// the drain thread, which trusts ring contents.
pub fn validate_submitted(rec: &[u8]) -> bool {
    struct C<'a>(&'a [u8], usize);
    impl C<'_> {
        fn take(&mut self, n: usize) -> Option<&[u8]> {
            let end = self.1.checked_add(n)?;
            let s = self.0.get(self.1..end)?;
            self.1 = end;
            Some(s)
        }
        fn u8(&mut self) -> Option<u8> { self.take(1).map(|b| b[0]) }
        fn u32(&mut self) -> Option<u32> { self.take(4).map(|b| u32::from_le_bytes(b.try_into().unwrap())) }
    }
    fn attrs(c: &mut C) -> Option<()> {
        let n = c.u32()?;
        for _ in 0..n {
            let tag = c.u8()?;
            c.take(4)?;
            match tag {
                A_STR => { let l = c.u32()? as usize; c.take(l)?; }
                A_I64 | A_F64 => { c.take(8)?; }
                A_BOOL => { c.take(1)?; }
                _ => return None,
            }
        }
        Some(())
    }
    let mut c = C(rec, 0);
    let ok = (|| -> Option<()> {
        match c.u8()? {
            K_FULL => { c.take(16 + 8 + 8 + 4 + 1 + 1 + 8 + 8)?; attrs(&mut c) }
            K_LOG => { c.take(8 + 16 + 8 + 1)?; let l = c.u32()? as usize; c.take(l)?; attrs(&mut c) }
            _ => None,
        }
    })();
    ok.is_some() && c.1 == rec.len()
}
