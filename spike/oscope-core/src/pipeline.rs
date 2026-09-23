//! Collector: one drain thread (rings -> rows) and one writer thread
//! (rows -> WAL -> chDB), connected by a bounded channel of reusable buffers.

use crate::assemble::Assembler;
use crate::chdb::Conn;
use crate::encode::{self, Resource};
use crate::recorder;
use crate::ring::Ring;
use crate::wal::Wal;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum InsertMode {
    /// chdb_stream_insert + chdb_stream_append, FORMAT RowBinary
    StreamRowBinary,
    /// chdb_query_n("INSERT ... FORMAT RowBinary\n" ++ bytes)
    QueryRowBinary,
}

pub struct Config {
    pub path: Option<String>,
    pub wal: Option<(String, bool)>,
    pub batch_rows: usize,
    pub flush_interval: Duration,
    pub mode: InsertMode,
    pub resource: Resource,
}

const T_TRACES: u8 = 1;
const T_LOGS: u8 = 2;

struct Batch {
    table: u8,
    rows: usize,
    buf: Vec<u8>,
    flush_seq: u64,
}

#[derive(Default)]
pub struct Stats {
    pub rows_traces: AtomicU64,
    pub rows_logs: AtomicU64,
    pub batches: AtomicU64,
    pub insert_ns: AtomicU64,
    pub wal_ns: AtomicU64,
    pub drain_ns: AtomicU64,
    pub drained_records: AtomicU64,
    pub errors: AtomicU64,
    pub batch_lat_us: Mutex<Vec<u64>>,
}

pub struct Pipeline {
    stop: Arc<AtomicBool>,
    flush_req: Arc<AtomicU64>,
    committed: Arc<(Mutex<u64>, Condvar)>,
    pub stats: Arc<Stats>,
    drain: Option<JoinHandle<()>>,
    writer: Option<JoinHandle<Conn>>,
    reader: Mutex<Option<Conn>>,
}

/// A Buffer table (otel_traces_buf / otel_logs_buf) sits in front of each
/// MergeTree table. Inserts land in memory and are queryable at once through
/// the _buf table; the Buffer writes N-to-3N-second chunks to MergeTree, so
/// parts are large and merges rare. Measured at 5k req/s: chDB CPU roughly
/// halved and on-disk size halved versus 8k-row direct inserts. The WAL covers
/// the Buffer's crash window; flush barriers force the Buffer out.
/// OSCOPE_BUFFER_SECS=N sets N (default 10); 0 disables the Buffer.
fn buffer_secs() -> Option<u32> {
    match std::env::var("OSCOPE_BUFFER_SECS") {
        Ok(v) => v.parse().ok().filter(|&n| n > 0),
        Err(_) => Some(10),
    }
}

fn buffer_ddl(conn: &Conn, secs: u32) -> Result<(), String> {
    for t in ["otel_traces", "otel_logs"] {
        conn.exec(&format!(
            "CREATE TABLE IF NOT EXISTS {t}_buf AS {t} ENGINE = Buffer(currentDatabase(), {t}, 1, {secs}, {}, 1000000, 10000000, 100000000, 1000000000)",
            secs * 3
        ))?;
    }
    Ok(())
}

fn buffer_flush(conn: &Conn) -> Result<(), String> {
    conn.exec("OPTIMIZE TABLE otel_traces_buf")?;
    conn.exec("OPTIMIZE TABLE otel_logs_buf")
}

pub fn insert(conn: &Conn, mode: InsertMode, table: u8, buf: &[u8]) -> Result<u64, String> {
    let ins = if table == T_TRACES { encode::TRACES_INSERT } else { encode::LOGS_INSERT };
    let buffered;
    let ins = if buffer_secs().is_some() {
        buffered = ins.replacen("INSERT INTO otel_traces ", "INSERT INTO otel_traces_buf ", 1).replacen("INSERT INTO otel_logs ", "INSERT INTO otel_logs_buf ", 1);
        buffered.as_str()
    } else {
        ins
    };
    match mode {
        InsertMode::StreamRowBinary => conn.stream_insert(ins, "RowBinary", &[buf]),
        InsertMode::QueryRowBinary => {
            let mut sql = Vec::with_capacity(ins.len() + 20 + buf.len());
            sql.extend_from_slice(ins.as_bytes());
            sql.extend_from_slice(b" FORMAT RowBinary\n");
            sql.extend_from_slice(buf);
            conn.query(&sql, "TabSeparated").map(|(_, r)| r)
        }
    }
}

impl Pipeline {
    pub fn start(cfg: Config) -> Result<Pipeline, String> {
        let conn = Conn::open(cfg.path.as_deref())?;
        conn.exec(&encode::ddl(encode::TRACES_DDL))?;
        conn.exec(&encode::ddl(encode::LOGS_DDL))?;
        let buffered = buffer_secs();
        if let Some(secs) = buffered {
            buffer_ddl(&conn, secs)?;
        }
        let mut wal = match &cfg.wal {
            Some((p, sync)) => {
                // recovery: re-insert anything the WAL holds (the spike truncates after replay;
                // a real design checkpoints and records a replay watermark instead)
                if std::path::Path::new(p).exists() {
                    for (t, _, payload) in Wal::replay(p).map_err(|e| e.to_string())? {
                        insert(&conn, cfg.mode, t, &payload)?;
                    }
                    // replayed rows sit in the in-memory Buffer: force them into
                    // MergeTree before the WAL that still covers them is removed
                    if buffered.is_some() {
                        buffer_flush(&conn)?;
                    }
                    std::fs::remove_file(p).map_err(|e| e.to_string())?;
                }
                Some(Wal::open(p, *sync).map_err(|e| e.to_string())?)
            }
            None => None,
        };
        let reader = Conn::open(cfg.path.as_deref())?;

        let stop = Arc::new(AtomicBool::new(false));
        let flush_req = Arc::new(AtomicU64::new(0));
        let committed = Arc::new((Mutex::new(0u64), Condvar::new()));
        let stats = Arc::new(Stats::default());

        let (full_tx, full_rx): (SyncSender<Batch>, Receiver<Batch>) = sync_channel(2);
        let (empty_tx, empty_rx) = sync_channel::<Vec<u8>>(8);
        for _ in 0..4 {
            empty_tx.send(Vec::with_capacity(1 << 20)).unwrap();
        }

        let mode = cfg.mode;
        let st = stats.clone();
        let cm = committed.clone();
        let writer = std::thread::Builder::new()
            .name("osc-writer".into())
            .spawn(move || {
                for b in full_rx {
                    let t0 = Instant::now();
                    if b.rows > 0 {
                        if let Some(w) = wal.as_mut() {
                            w.append(b.table, b.rows as u32, &b.buf).expect("wal append");
                        }
                        let t1 = Instant::now();
                        match insert(&conn, mode, b.table, &b.buf) {
                            Ok(_) => {
                                let c = if b.table == T_TRACES { &st.rows_traces } else { &st.rows_logs };
                                c.fetch_add(b.rows as u64, Ordering::Relaxed);
                            }
                            Err(e) => {
                                eprintln!("insert error: {e}");
                                st.errors.fetch_add(1, Ordering::Relaxed);
                            }
                        }
                        let t2 = Instant::now();
                        st.wal_ns.fetch_add((t1 - t0).as_nanos() as u64, Ordering::Relaxed);
                        st.insert_ns.fetch_add((t2 - t1).as_nanos() as u64, Ordering::Relaxed);
                        st.batches.fetch_add(1, Ordering::Relaxed);
                        st.batch_lat_us.lock().unwrap().push((t2 - t0).as_micros() as u64);
                    }
                    if b.flush_seq > 0 && buffered.is_some() {
                        if let Err(e) = buffer_flush(&conn) {
                            eprintln!("buffer flush: {e}");
                            st.errors.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                    if b.flush_seq > 0 {
                        let (m, cv) = &*cm;
                        *m.lock().unwrap() = b.flush_seq;
                        cv.notify_all();
                    }
                    let mut buf = b.buf;
                    buf.clear();
                    let _ = empty_tx.try_send(buf);
                }
                conn
            })
            .unwrap();

        let stop2 = stop.clone();
        let fr = flush_req.clone();
        let st = stats.clone();
        let batch_rows = cfg.batch_rows;
        let interval = cfg.flush_interval;
        let res = cfg.resource;
        let drain = std::thread::Builder::new()
            .name("osc-drain".into())
            .spawn(move || {
                let mut asm = Assembler::new(&res);
                let mut rings: Vec<Arc<Ring>> = Vec::new();
                let mut scratch = Vec::with_capacity(4096);
                let mut last_ship = Instant::now();
                let mut handled_flush = 0u64;
                // Wake-up policy. A timed wake-up costs tens of microseconds of
                // CPU (far more on a VM, where each is a guest exit), while
                // assembling a record costs ~120 ns. So the drain thread parks
                // for long stretches: busy passes loop at once, light passes
                // park 10 ms, empty passes back off to 50 ms. It is unparked
                // early by flush() and by any producer whose ring passes half
                // full, so neither flush latency nor ring capacity suffers.
                recorder::set_drain_thread(Some(std::thread::current()));
                let mut idle_shift = 0u32;
                let ship = |asm: &mut Assembler, table: u8, flush_seq: u64| {
                    let spare = empty_rx.recv().unwrap_or_default();
                    let (rows, buf) = if table == T_TRACES {
                        let rows = asm.traces.rows;
                        asm.traces.rows = 0;
                        (rows, std::mem::replace(&mut asm.traces.buf, spare))
                    } else {
                        let rows = asm.logs.rows;
                        asm.logs.rows = 0;
                        (rows, std::mem::replace(&mut asm.logs.buf, spare))
                    };
                    full_tx.send(Batch { table, rows, buf, flush_seq }).unwrap();
                };
                loop {
                    let stopping = stop2.load(Ordering::Acquire);
                    let want_flush = fr.load(Ordering::Acquire);
                    rings.clear();
                    rings.extend(recorder::global().rings.lock().unwrap().iter().cloned());
                    let mut n = 0usize;
                    for r in &rings {
                        // bounded per-ring slice keeps latency fair across threads
                        let t0 = Instant::now();
                        let mut k = 0;
                        while k < 4096 && r.pop(&mut scratch) {
                            asm.feed(&scratch);
                            k += 1;
                        }
                        n += k;
                        st.drain_ns.fetch_add(t0.elapsed().as_nanos() as u64, Ordering::Relaxed);
                        st.drained_records.fetch_add(k as u64, Ordering::Relaxed);
                        if asm.traces.rows >= batch_rows {
                            ship(&mut asm, T_TRACES, 0);
                            last_ship = Instant::now();
                        }
                        if asm.logs.rows >= batch_rows {
                            ship(&mut asm, T_LOGS, 0);
                        }
                    }
                    let all_empty = n == 0;
                    if (want_flush > handled_flush && all_empty) || last_ship.elapsed() >= interval || (stopping && all_empty) {
                        let seq = if want_flush > handled_flush && all_empty { want_flush } else { 0 };
                        ship(&mut asm, T_LOGS, 0);
                        ship(&mut asm, T_TRACES, seq);
                        if seq > 0 {
                            handled_flush = seq;
                        }
                        last_ship = Instant::now();
                    }
                    if stopping && all_empty {
                        break;
                    }
                    // drop rings whose thread exited and that are now empty
                    {
                        let mut g = recorder::global().rings.lock().unwrap();
                        g.retain(|r| !(r.thread_gone.load(Ordering::Acquire) && r.is_empty()));
                    }
                    for r in &rings {
                        r.wake_sent.store(false, Ordering::Relaxed);
                    }
                    if all_empty {
                        std::thread::park_timeout(Duration::from_millis((10u64 << idle_shift).min(50)));
                        idle_shift = (idle_shift + 1).min(3);
                    } else {
                        idle_shift = 0;
                        if n < 4096 {
                            std::thread::park_timeout(Duration::from_millis(10));
                        }
                    }
                }
                drop(full_tx);
            })
            .unwrap();

        Ok(Pipeline {
            stop,
            flush_req,
            committed,
            stats,
            drain: Some(drain),
            writer: Some(writer),
            reader: Mutex::new(Some(reader)),
        })
    }

    /// Barrier: everything recorded before this call is inserted (and, with a
    /// WAL, persisted) when it returns true.
    pub fn flush(&self, timeout: Duration) -> bool {
        let seq = self.flush_req.fetch_add(1, Ordering::AcqRel) + 1;
        recorder::wake_drain();
        let (m, cv) = &*self.committed;
        let g = m.lock().unwrap();
        let (g, _) = cv.wait_timeout_while(g, timeout, |c| *c < seq).unwrap();
        *g >= seq
    }

    pub fn query(&self, sql: &str, format: &str) -> Result<String, String> {
        self.reader.lock().unwrap().as_ref().unwrap().query_string(sql, format)
    }

    pub fn stop(mut self) -> Conn {
        self.stop.store(true, Ordering::Release);
        recorder::wake_drain();
        self.drain.take().unwrap().join().unwrap();
        recorder::set_drain_thread(None);
        drop(self.reader.lock().unwrap().take());
        self.writer.take().unwrap().join().unwrap()
    }
}
