//! Hot path. Everything here runs on the instrumented thread: it must not
//! lock, allocate (after the thread's first call), or wait.

use crate::ring::{Ring, Writer};
use std::cell::UnsafeCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

pub const K_START: u8 = 1;
pub const K_ATTR: u8 = 2;
pub const K_END: u8 = 3;
pub const K_FULL: u8 = 4;
pub const K_LOG: u8 = 5;

pub const A_STR: u8 = 1;
pub const A_I64: u8 = 2;
pub const A_F64: u8 = 3;
pub const A_BOOL: u8 = 4;

#[derive(Clone, Copy)]
pub enum AttrVal<'a> {
    Str(&'a [u8]),
    I64(i64),
    F64(f64),
    Bool(bool),
}

impl AttrVal<'_> {
    #[inline]
    pub fn encoded_len(&self) -> usize {
        1 + 4 + match self {
            AttrVal::Str(s) => 4 + s.len(),
            AttrVal::I64(_) | AttrVal::F64(_) => 8,
            AttrVal::Bool(_) => 1,
        }
    }
    #[inline]
    pub fn write(&self, key: u32, w: &mut Writer) {
        match *self {
            AttrVal::Str(s) => {
                w.u8(A_STR);
                w.u32(key);
                w.u32(s.len() as u32);
                w.bytes(s);
            }
            AttrVal::I64(v) => {
                w.u8(A_I64);
                w.u32(key);
                w.u64(v as u64);
            }
            AttrVal::F64(v) => {
                w.u8(A_F64);
                w.u32(key);
                w.u64(v.to_bits());
            }
            AttrVal::Bool(v) => {
                w.u8(A_BOOL);
                w.u32(key);
                w.u8(v as u8);
            }
        }
    }
}

// ---------------------------------------------------------------- global

pub struct Interner {
    map: HashMap<Box<[u8]>, u32>,
    pub strings: Vec<Arc<str>>,
}

pub struct Global {
    pub rings: Mutex<Vec<Arc<Ring>>>,
    pub interner: RwLock<Interner>,
    pub ring_bytes: AtomicUsize,
}

pub fn global() -> &'static Global {
    static G: OnceLock<Global> = OnceLock::new();
    G.get_or_init(|| Global {
        rings: Mutex::new(Vec::new()),
        interner: RwLock::new(Interner { map: HashMap::new(), strings: vec![Arc::from("")] }),
        ring_bytes: AtomicUsize::new(1 << 20),
    })
}

/// Intern a string (span names, attribute keys). Setup-time, not hot path.
pub fn intern(s: &[u8]) -> u32 {
    let g = global();
    if let Some(&id) = g.interner.read().unwrap().map.get(s) {
        return id;
    }
    let mut w = g.interner.write().unwrap();
    if let Some(&id) = w.map.get(s) {
        return id;
    }
    let id = w.strings.len() as u32;
    w.strings.push(Arc::from(String::from_utf8_lossy(s).as_ref()));
    w.map.insert(s.into(), id);
    id
}

// ---------------------------------------------------------------- thread-local

const MAX_DEPTH: usize = 64;
/// Bytes held back per open span so its END always fits.
const END_RESERVE: usize = 4 + 1 + 8 + 8 + 1;
pub static DROPPED: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
/// Whole spans refused at start (ring full or too deep).
pub static DROPPED_SPANS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

struct Local {
    ring: Arc<Ring>,
    stack: [(u128, u64); MAX_DEPTH],
    depth: usize,
    rng: u64,
}

impl Drop for Local {
    fn drop(&mut self) {
        self.ring.thread_gone.store(true, Ordering::Release);
    }
}

thread_local! {
    static LOCAL: UnsafeCell<Option<Local>> = const { UnsafeCell::new(None) };
}

#[inline]
fn with_local<R>(f: impl FnOnce(&mut Local) -> R) -> R {
    LOCAL.with(|cell| {
        let slot = unsafe { &mut *cell.get() };
        if slot.is_none() {
            *slot = Some(register_thread());
        }
        f(slot.as_mut().unwrap())
    })
}

#[cold]
fn register_thread() -> Local {
    let g = global();
    let ring = Arc::new(Ring::new(g.ring_bytes.load(Ordering::Relaxed)));
    g.rings.lock().unwrap().push(ring.clone());
    let seed = now_ns() ^ (&ring as *const _ as u64).rotate_left(32) ^ 0x9E37_79B9_7F4A_7C15;
    Local { ring, stack: [(0, 0); MAX_DEPTH], depth: 0, rng: seed | 1 }
}

#[inline]
fn next_rand(state: &mut u64) -> u64 {
    // wyrand
    *state = state.wrapping_add(0xa076_1d64_78bd_642f);
    let t = (*state as u128).wrapping_mul((*state ^ 0xe703_7ed1_a0b4_28db) as u128);
    ((t >> 64) as u64) ^ (t as u64)
}

#[inline]
pub fn now_ns() -> u64 {
    let mut ts = libc_timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { clock_gettime(0 /* CLOCK_REALTIME */, &mut ts) };
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

#[repr(C)]
struct libc_timespec {
    tv_sec: i64,
    tv_nsec: i64,
}
extern "C" {
    fn clock_gettime(clk: i32, ts: *mut libc_timespec) -> i32;
}

// ---------------------------------------------------------------- span API

/// Opaque span handle: the span id. 0 means "not recorded" (dropped).
pub type SpanId = u64;

#[inline]
pub fn span_start(name: u32, kind: u8) -> SpanId {
    with_local(|l| {
        let span = next_rand(&mut l.rng) | 1;
        let (trace, parent) = if l.depth > 0 {
            let (t, p) = l.stack[l.depth - 1];
            (t, p)
        } else {
            (((next_rand(&mut l.rng) as u128) << 64) | next_rand(&mut l.rng) as u128, 0)
        };
        let t = now_ns();
        if l.depth >= MAX_DEPTH {
            l.ring.dropped.fetch_add(1, Ordering::Relaxed);
            DROPPED.fetch_add(1, Ordering::Relaxed);
            DROPPED_SPANS.fetch_add(1, Ordering::Relaxed);
            return 0;
        }
        let ok = l.ring.push_with(1 + 16 + 8 + 8 + 4 + 1 + 8, END_RESERVE * (l.depth + 1), |w| {
            w.u8(K_START);
            w.bytes(&trace.to_be_bytes());
            w.u64(span);
            w.u64(parent);
            w.u32(name);
            w.u8(kind);
            w.u64(t);
        });
        if !ok {
            DROPPED_SPANS.fetch_add(1, Ordering::Relaxed);
            return 0;
        }
        l.stack[l.depth] = (trace, span);
        l.depth += 1;
        span
    })
}

#[inline]
pub fn span_attr(span: SpanId, key: u32, v: AttrVal) {
    if span == 0 {
        return;
    }
    with_local(|l| {
        l.ring.push_with(1 + 8 + v.encoded_len(), END_RESERVE * l.depth, |w| {
            w.u8(K_ATTR);
            w.u64(span);
            v.write(key, w);
        });
    })
}

#[inline]
pub fn span_end(span: SpanId, status: u8) {
    if span == 0 {
        return;
    }
    with_local(|l| {
        let t = now_ns();
        l.ring.push_with(1 + 8 + 8 + 1, 0, |w| {
            w.u8(K_END);
            w.u64(span);
            w.u64(t);
            w.u8(status);
        });
        if l.depth > 0 && l.stack[l.depth - 1].1 == span {
            l.depth -= 1;
        }
    })
}

/// One-shot record of a finished span: one ring reservation, one FFI crossing.
pub struct SpanRec<'a> {
    pub trace_id: [u8; 16],
    pub span_id: u64,
    pub parent_span_id: u64,
    pub name: u32,
    pub kind: u8,
    pub status: u8,
    pub start_ns: u64,
    pub end_ns: u64,
    pub attrs: &'a [(u32, AttrVal<'a>)],
}

#[inline]
pub fn record_span(r: &SpanRec) -> bool {
    with_local(|l| {
        let mut span = r.span_id;
        if span == 0 {
            span = next_rand(&mut l.rng) | 1;
        }
        let mut trace = r.trace_id;
        if trace == [0; 16] {
            trace = if l.depth > 0 {
                l.stack[l.depth - 1].0.to_be_bytes()
            } else {
                (((next_rand(&mut l.rng) as u128) << 64) | next_rand(&mut l.rng) as u128).to_be_bytes()
            };
        }
        let alen: usize = r.attrs.iter().map(|(_, v)| v.encoded_len()).sum();
        l.ring.push_with(1 + 16 + 8 + 8 + 4 + 1 + 1 + 8 + 8 + 4 + alen, END_RESERVE * l.depth, |w| {
            w.u8(K_FULL);
            w.bytes(&trace);
            w.u64(span);
            w.u64(r.parent_span_id);
            w.u32(r.name);
            w.u8(r.kind);
            w.u8(r.status);
            w.u64(r.start_ns);
            w.u64(r.end_ns);
            w.u32(r.attrs.len() as u32);
            for (k, v) in r.attrs {
                v.write(*k, w);
            }
        })
    })
}

/// Log record correlated with the current span (if any).
#[inline]
pub fn log(severity: u8, body: &[u8], attrs: &[(u32, AttrVal)]) -> bool {
    with_local(|l| {
        let (trace, span) = if l.depth > 0 { l.stack[l.depth - 1] } else { (0, 0) };
        let t = now_ns();
        let alen: usize = attrs.iter().map(|(_, v)| v.encoded_len()).sum();
        l.ring.push_with(1 + 8 + 16 + 8 + 1 + 4 + body.len() + 4 + alen, END_RESERVE * l.depth, |w| {
            w.u8(K_LOG);
            w.u64(t);
            w.bytes(&trace.to_be_bytes());
            w.u64(span);
            w.u8(severity);
            w.u32(body.len() as u32);
            w.bytes(body);
            w.u32(attrs.len() as u32);
            for (k, v) in attrs {
                v.write(*k, w);
            }
        })
    })
}

/// Copy one pre-encoded K_FULL or K_LOG record (validated by the caller) into
/// this thread's ring. Used by `osc_submit` for hosts that encode records
/// themselves (Go, JVM, Jolt) and cross the FFI boundary once per batch.
#[inline]
pub fn submit_raw(rec: &[u8]) -> bool {
    with_local(|l| l.ring.push_with(rec.len(), END_RESERVE * l.depth, |w| w.bytes(rec)))
}

static DRAIN_THREAD: Mutex<Option<std::thread::Thread>> = Mutex::new(None);

/// Register the drain thread so producers and flush() can unpark it.
pub fn set_drain_thread(t: Option<std::thread::Thread>) {
    *DRAIN_THREAD.lock().unwrap() = t;
}

#[cold]
pub fn wake_drain() {
    if let Some(t) = DRAIN_THREAD.lock().unwrap().as_ref() {
        t.unpark();
    }
}

pub fn dropped_total() -> u64 {
    DROPPED.load(Ordering::Relaxed)
}
