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

pub fn insert(conn: &Conn, mode: InsertMode, table: u8, buf: &[u8]) -> Result<u64, String> {
    let ins = if table == T_TRACES { encode::TRACES_INSERT } else { encode::LOGS_INSERT };
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
        conn.exec(encode::TRACES_DDL)?;
        conn.exec(encode::LOGS_DDL)?;
        let mut wal = match &cfg.wal {
            Some((p, sync)) => {
                // recovery: re-insert anything the WAL holds (the spike truncates after replay;
                // a real design checkpoints and records a replay watermark instead)
                if std::path::Path::new(p).exists() {
                    for (t, _, payload) in Wal::replay(p).map_err(|e| e.to_string())? {
                        insert(&conn, cfg.mode, t, &payload)?;
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
                    if all_empty {
                        std::thread::sleep(Duration::from_micros(200));
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
        self.drain.take().unwrap().join().unwrap();
        drop(self.reader.lock().unwrap().take());
        self.writer.take().unwrap().join().unwrap()
    }
}
