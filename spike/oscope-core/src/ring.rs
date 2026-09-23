//! Per-thread single-producer / single-consumer byte ring.
//!
//! The producer is the instrumented thread; the consumer is the drain thread.
//! Records are length-prefixed and may wrap; the producer never blocks and
//! never allocates. When a record does not fit it is dropped and counted.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

#[repr(align(64))]
struct Padded<T>(T);

pub struct Ring {
    buf: Box<[UnsafeCell<u8>]>,
    mask: usize,
    head: Padded<AtomicUsize>, // written by producer
    tail: Padded<AtomicUsize>, // written by consumer
    pub dropped: AtomicU64,
    pub thread_gone: std::sync::atomic::AtomicBool,
    /// Set by the producer when it has unparked the drain thread for this ring
    /// (ring past half full); cleared by the drain thread after draining it.
    pub wake_sent: std::sync::atomic::AtomicBool,
}

unsafe impl Sync for Ring {}
unsafe impl Send for Ring {}

impl Ring {
    pub fn new(cap_pow2: usize) -> Ring {
        assert!(cap_pow2.is_power_of_two());
        let buf = (0..cap_pow2).map(|_| UnsafeCell::new(0u8)).collect::<Vec<_>>();
        // pre-fault so the producer never takes a first-touch page fault
        for i in (0..cap_pow2).step_by(4096) {
            unsafe { std::ptr::write_volatile(buf[i].get(), 0) };
        }
        Ring {
            buf: buf.into_boxed_slice(),
            mask: cap_pow2 - 1,
            head: Padded(AtomicUsize::new(0)),
            tail: Padded(AtomicUsize::new(0)),
            dropped: AtomicU64::new(0),
            thread_gone: std::sync::atomic::AtomicBool::new(false),
            wake_sent: std::sync::atomic::AtomicBool::new(false),
        }
    }

    #[inline]
    fn base(&self) -> *mut u8 {
        self.buf.as_ptr() as *mut u8
    }

    #[inline]
    unsafe fn copy_in(&self, pos: usize, src: &[u8]) {
        let cap = self.mask + 1;
        let off = pos & self.mask;
        let first = src.len().min(cap - off);
        std::ptr::copy_nonoverlapping(src.as_ptr(), self.base().add(off), first);
        if first < src.len() {
            std::ptr::copy_nonoverlapping(src.as_ptr().add(first), self.base(), src.len() - first);
        }
    }

    #[inline]
    unsafe fn copy_out(&self, pos: usize, dst: &mut [u8]) {
        let cap = self.mask + 1;
        let off = pos & self.mask;
        let first = dst.len().min(cap - off);
        std::ptr::copy_nonoverlapping(self.base().add(off), dst.as_mut_ptr(), first);
        if first < dst.len() {
            std::ptr::copy_nonoverlapping(self.base(), dst.as_mut_ptr().add(first), dst.len() - first);
        }
    }

    /// Reserve `len` bytes, let `fill` write them through a `Writer`, publish.
    /// Returns false (and counts a drop) when the ring is full.
    #[inline]
    /// `reserve` = extra bytes that must remain free afterwards (space held back
    /// for END records of spans already open, so a span is never half-recorded).
    pub fn push_with(&self, len: usize, reserve: usize, fill: impl FnOnce(&mut Writer)) -> bool {
        let total = 4 + len;
        let head = self.head.0.load(Ordering::Relaxed);
        let tail = self.tail.0.load(Ordering::Acquire);
        if total + reserve > (self.mask + 1) - (head - tail) {
            self.dropped.fetch_add(1, Ordering::Relaxed);
            crate::recorder::DROPPED.fetch_add(1, Ordering::Relaxed);
            return false;
        }
        let off = head & self.mask;
        let lin = if off + total <= self.mask + 1 { unsafe { self.base().add(off) } } else { std::ptr::null_mut() };
        let mut w = Writer { ring: self, lin, pos: head, end: head + total };
        w.u32(len as u32);
        fill(&mut w);
        debug_assert_eq!(w.pos, w.end, "record length mismatch");
        self.head.0.store(head + total, Ordering::Release);
        // Past half full: wake the drain thread once, rather than have it poll often.
        if (head + total - tail) > (self.mask + 1) / 2 && !self.wake_sent.load(Ordering::Relaxed) {
            self.wake_sent.store(true, Ordering::Relaxed);
            crate::recorder::wake_drain();
        }
        true
    }

    /// Pop one record into `scratch`. Returns false when empty.
    pub fn pop(&self, scratch: &mut Vec<u8>) -> bool {
        let tail = self.tail.0.load(Ordering::Relaxed);
        let head = self.head.0.load(Ordering::Acquire);
        if tail == head {
            return false;
        }
        let mut lenb = [0u8; 4];
        unsafe { self.copy_out(tail, &mut lenb) };
        let len = u32::from_le_bytes(lenb) as usize;
        scratch.clear();
        scratch.resize(len, 0);
        unsafe { self.copy_out(tail + 4, scratch) };
        self.tail.0.store(tail + 4 + len, Ordering::Release);
        true
    }

    pub fn is_empty(&self) -> bool {
        self.tail.0.load(Ordering::Relaxed) == self.head.0.load(Ordering::Acquire)
    }
}

pub struct Writer<'a> {
    ring: &'a Ring,
    /// non-null when the whole record is contiguous: plain stores, no masking
    lin: *mut u8,
    pos: usize,
    end: usize,
}

impl Writer<'_> {
    #[inline]
    pub fn bytes(&mut self, b: &[u8]) {
        debug_assert!(self.pos + b.len() <= self.end);
        unsafe {
            if !self.lin.is_null() {
                std::ptr::copy_nonoverlapping(b.as_ptr(), self.lin, b.len());
                self.lin = self.lin.add(b.len());
            } else {
                self.ring.copy_in(self.pos, b)
            }
        }
        self.pos += b.len();
    }
    #[inline]
    pub fn u8(&mut self, v: u8) {
        self.bytes(&[v])
    }
    #[inline]
    pub fn u32(&mut self, v: u32) {
        self.bytes(&v.to_le_bytes())
    }
    #[inline]
    pub fn u64(&mut self, v: u64) {
        self.bytes(&v.to_le_bytes())
    }
}

/// Cursor over a popped record.
pub struct Reader<'a> {
    pub b: &'a [u8],
    pub p: usize,
}

impl<'a> Reader<'a> {
    #[inline]
    pub fn u8(&mut self) -> u8 {
        let v = self.b[self.p];
        self.p += 1;
        v
    }
    #[inline]
    pub fn u32(&mut self) -> u32 {
        let v = u32::from_le_bytes(self.b[self.p..self.p + 4].try_into().unwrap());
        self.p += 4;
        v
    }
    #[inline]
    pub fn u64(&mut self) -> u64 {
        let v = u64::from_le_bytes(self.b[self.p..self.p + 8].try_into().unwrap());
        self.p += 8;
        v
    }
    #[inline]
    pub fn bytes(&mut self, n: usize) -> &'a [u8] {
        let v = &self.b[self.p..self.p + n];
        self.p += n;
        v
    }
    pub fn done(&self) -> bool {
        self.p >= self.b.len()
    }
}
