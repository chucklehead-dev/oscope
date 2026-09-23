use oscope_core::chdb::Conn;
use oscope_core::encode::{self, JsonTraces, Resource, RowBinaryTraces, SpanRow};
use oscope_core::pipeline::{self, Config, InsertMode, Pipeline};
use oscope_core::recorder::{self, AttrVal, SpanRec};
use oscope_core::wal::Wal;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Barrier};
use std::time::{Duration, Instant};

// ------------------------------------------------ allocation counting

struct Counting;
thread_local! { static COUNT: Cell<bool> = const { Cell::new(false) }; }
thread_local! { static ALLOCS_TL: Cell<u64> = const { Cell::new(0) }; }
struct AllocsTl;
impl AllocsTl { fn load(&self, _: Ordering) -> u64 { ALLOCS_TL.with(|c| c.get()) } }
static ALLOCS: AllocsTl = AllocsTl;
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        if COUNT.with(|c| c.get()) {
            ALLOCS_TL.with(|c| c.set(c.get() + 1));
        }
        System.alloc(l)
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        System.dealloc(p, l)
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, n: usize) -> *mut u8 {
        if COUNT.with(|c| c.get()) {
            ALLOCS_TL.with(|c| c.set(c.get() + 1));
        }
        System.realloc(p, l, n)
    }
}
#[global_allocator]
static GA: Counting = Counting;

// ------------------------------------------------ helpers

fn resource() -> Resource {
    Resource {
        service_name: "checkout".into(),
        scope_name: "bench".into(),
        scope_version: "0.0.1".into(),
        attrs: vec![
            ("service.name".into(), "checkout".into()),
            ("host.name".into(), "bench-host".into()),
            ("telemetry.sdk.language".into(), "rust".into()),
        ],
    }
}

fn pct(v: &mut [u64], p: f64) -> u64 {
    if v.is_empty() {
        return 0;
    }
    v.sort_unstable();
    let i = ((p / 100.0) * v.len() as f64).ceil() as usize;
    v[i.saturating_sub(1).min(v.len() - 1)]
}

struct Keys {
    names: [u32; 4],
    route: u32,
    status: u32,
    tier: u32,
}
fn keys() -> Keys {
    Keys {
        names: [
            recorder::intern(b"GET /api/orders/{id}"),
            recorder::intern(b"db.query"),
            recorder::intern(b"cache.get"),
            recorder::intern(b"render"),
        ],
        route: recorder::intern(b"http.route"),
        status: recorder::intern(b"http.response.status_code"),
        tier: recorder::intern(b"user.tier"),
    }
}

#[inline]
fn one_span_incremental(k: &Keys, i: u64) {
    let s = recorder::span_start(k.names[(i & 3) as usize], 2);
    recorder::span_attr(s, k.route, AttrVal::Str(b"/api/orders/{id}"));
    recorder::span_attr(s, k.status, AttrVal::I64(200 + (i % 3) as i64));
    recorder::span_attr(s, k.tier, AttrVal::Str(if i & 1 == 0 { b"gold" } else { b"free" }));
    recorder::span_end(s, 0);
}

#[inline]
fn one_span_record(k: &Keys, i: u64) {
    let t = recorder::now_ns();
    let attrs = [
        (k.route, AttrVal::Str(b"/api/orders/{id}")),
        (k.status, AttrVal::I64(200 + (i % 3) as i64)),
        (k.tier, AttrVal::Str(if i & 1 == 0 { b"gold" } else { b"free" })),
    ];
    recorder::record_span(&SpanRec {
        trace_id: [0; 16],
        span_id: 0,
        parent_span_id: 0,
        name: k.names[(i & 3) as usize],
        kind: 2,
        status: 0,
        start_ns: t,
        end_ns: t + 1500,
        attrs: &attrs,
    });
}

fn arg<'a>(args: &'a [String], flag: &str) -> Option<&'a str> {
    args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1)).map(|s| s.as_str())
}

// ------------------------------------------------ hot: producer cost only

fn bench_hot(args: &[String]) {
    let threads: usize = arg(args, "--threads").unwrap_or("1").parse().unwrap();
    let n: u64 = arg(args, "--spans").unwrap_or("500000").parse().unwrap();
    let api = arg(args, "--api").unwrap_or("incremental").to_string();
    recorder::global().ring_bytes.store(256 << 20, Ordering::Relaxed);
    let k = Arc::new(keys());
    let bar = Arc::new(Barrier::new(threads));
    let hs: Vec<_> = (0..threads)
        .map(|_| {
            let k = k.clone();
            let bar = bar.clone();
            let api = api.clone();
            std::thread::spawn(move || {
                // first call registers the thread's ring (the only allocation)
                one_span_incremental(&k, 0);
                bar.wait();
                let mut samples = Vec::with_capacity((n / 1024) as usize + 1);
                let a0 = ALLOCS.load(Ordering::Relaxed);
                let mut chunk = Instant::now();
                let t0 = Instant::now();
                COUNT.with(|c| c.set(true));
                for i in 0..n {
                    if api == "record" { one_span_record(&k, i) } else { one_span_incremental(&k, i) }
                    if i & 1023 == 1023 {
                        let now = Instant::now();
                        COUNT.with(|c| c.set(false));
                        samples.push((now - chunk).as_nanos() as u64 / 1024);
                        COUNT.with(|c| c.set(true));
                        chunk = now;
                    }
                }
                let el = t0.elapsed();
                COUNT.with(|c| c.set(false));
                let allocs = ALLOCS.load(Ordering::Relaxed) - a0;
                (el, allocs, samples)
            })
        })
        .collect();
    let mut all = Vec::new();
    let mut tot_ns = 0u128;
    let mut allocs = 0;
    for h in hs {
        let (el, a, s) = h.join().unwrap();
        tot_ns += el.as_nanos();
        allocs += a;
        all.extend(s);
    }
    let mean = tot_ns as f64 / (threads as f64 * n as f64);
    println!(
        "hot api={api} threads={threads} spans/thread={n}: mean {:.1} ns/span, 1024-span chunk mean p50 {} ns p99 {} ns, aggregate {:.2} M spans/s, producer allocations {allocs}, dropped {}",
        mean,
        pct(&mut all.clone(), 50.0),
        pct(&mut all, 99.0),
        (threads as f64 * n as f64) / (tot_ns as f64 / threads as f64) * 1e3,
        recorder::dropped_total()
    );
}

// ------------------------------------------------ insert: encode + chDB formats

fn synth_rows(n: usize) -> Vec<([u8; 16], u64, u64, u64, u64, u32, i64, bool)> {
    let base = recorder::now_ns();
    (0..n)
        .map(|i| {
            let mut t = [0u8; 16];
            t[..8].copy_from_slice(&((i / 5) as u64 * 0x9E37_79B9).to_be_bytes());
            t[8..].copy_from_slice(&(i as u64 ^ 0xABCD).to_be_bytes());
            (t, i as u64 + 1, if i % 5 == 0 { 0 } else { i as u64 }, base + i as u64 * 1000, base + i as u64 * 1000 + 1500, (i & 3) as u32, 200 + (i % 3) as i64, i & 1 == 0)
        })
        .collect()
}

const NAMES: [&str; 4] = ["GET /api/orders/{id}", "db.query", "cache.get", "render"];

fn encode_batch(rows: &[([u8; 16], u64, u64, u64, u64, u32, i64, bool)], rb: &mut RowBinaryTraces, js: Option<&mut JsonTraces>) {
    let mut js = js;
    for r in rows {
        let status = itoa::Buffer::new().format(r.6).to_string();
        let attrs: [(&str, &[u8]); 3] = [
            ("http.route", b"/api/orders/{id}"),
            ("http.response.status_code", status.as_bytes()),
            ("user.tier", if r.7 { b"gold" } else { b"free" }),
        ];
        let mut it = attrs.iter().copied();
        let row = SpanRow { start_ns: r.3, end_ns: r.4, trace_id: &r.0, span_id: r.1, parent_span_id: r.2, name: NAMES[r.5 as usize], kind: 2, status: 0, attrs: &mut it };
        match js.as_deref_mut() {
            Some(j) => j.push(row),
            None => rb.push(row, 3),
        }
    }
}

fn bench_insert(args: &[String]) {
    let path = arg(args, "--path");
    let total: usize = arg(args, "--rows").unwrap_or("200000").parse().unwrap();
    let wal_path = arg(args, "--wal");
    let conn = Conn::open(path).unwrap();
    conn.exec(encode::TRACES_DDL).unwrap();
    // Buffer engine in front of MergeTree: small inserts land in RAM, flushed as big parts
    conn.exec("CREATE TABLE IF NOT EXISTS otel_traces_buf AS otel_traces ENGINE = Buffer(currentDatabase(), otel_traces, 1, 1, 5, 10000, 1000000, 10000000, 100000000)").unwrap();
    let rows = synth_rows(total);
    let res = resource();
    println!("insert: storage={} rows={total} (3 span attrs + 3 resource attrs per row)", path.unwrap_or(":memory:"));
    let batches: Vec<usize> = arg(args, "--batches").unwrap_or("512,10000").split(',').map(|b| b.parse().unwrap()).collect();
    for &batch in &batches {
        let only = arg(args, "--fmt");
        for fmt in ["json-query", "rb-query", "rb-stream", "rb-stream+wal-fsync", "rb-stream-buffer"] {
            if only.is_some_and(|o| o != fmt) {
                continue;
            }
            if fmt.contains("wal") && wal_path.is_none() {
                continue;
            }
            conn.exec("TRUNCATE TABLE otel_traces").unwrap();
            let mut wal = wal_path.map(|p| {
                let _ = std::fs::remove_file(p);
                Wal::open(p, true).unwrap()
            });
            let mut rb = RowBinaryTraces::new(&res);
            let mut js = JsonTraces::new(&res);
            let mut sql = Vec::new();
            let mut lat = Vec::new();
            let mut enc_ns = 0u128;
            let mut bytes = 0usize;
            let t_all = Instant::now();
            for chunk in rows.chunks(batch) {
                let t0 = Instant::now();
                rb.clear();
                js.clear();
                if fmt == "json-query" {
                    encode_batch(chunk, &mut rb, Some(&mut js));
                    sql.clear();
                    sql.extend_from_slice(encode::TRACES_INSERT.as_bytes());
                    sql.extend_from_slice(b" FORMAT JSONEachRow\n");
                    sql.extend_from_slice(&js.buf);
                } else {
                    encode_batch(chunk, &mut rb, None);
                }
                let t1 = Instant::now();
                enc_ns += (t1 - t0).as_nanos();
                let r = match fmt {
                    "json-query" => {
                        bytes += js.buf.len();
                        conn.query(&sql, "TabSeparated").map(|x| x.1)
                    }
                    "rb-query" => {
                        bytes += rb.buf.len();
                        pipeline::insert(&conn, InsertMode::QueryRowBinary, 1, &rb.buf)
                    }
                    "rb-stream-buffer" => {
                        bytes += rb.buf.len();
                        conn.stream_insert(&encode::TRACES_INSERT.replace("otel_traces", "otel_traces_buf"), "RowBinary", &[&rb.buf]).map(|_| 0)
                    }
                    _ => {
                        bytes += rb.buf.len();
                        if let Some(w) = wal.as_mut() {
                            w.append(1, chunk.len() as u32, &rb.buf).unwrap();
                        }
                        pipeline::insert(&conn, InsertMode::StreamRowBinary, 1, &rb.buf)
                    }
                };
                r.unwrap();
                lat.push(t0.elapsed().as_micros() as u64);
            }
            if fmt == "rb-stream-buffer" {
                conn.exec("OPTIMIZE TABLE otel_traces_buf").unwrap(); // flush buffer into MergeTree
            }
            let el = t_all.elapsed();
            let count = conn.query_string("SELECT count() FROM otel_traces", "TabSeparated").unwrap();
            assert_eq!(count.trim().parse::<usize>().unwrap(), total, "row count mismatch");
            println!(
                "  batch={batch:>5} {fmt:<20} {:>9.0} rows/s  batch p50 {:>7.2} ms p99 {:>7.2} ms  encode {:>5.1}% of time  {:>6.1} B/row  verified {total}",
                total as f64 / el.as_secs_f64(),
                pct(&mut lat.clone(), 50.0) as f64 / 1000.0,
                pct(&mut lat, 99.0) as f64 / 1000.0,
                enc_ns as f64 / el.as_nanos() as f64 * 100.0,
                bytes as f64 / total as f64,
            );
        }
    }
}

// ------------------------------------------------ e2e: producers -> rings -> drain -> chDB

fn bench_e2e(args: &[String]) {
    let threads: usize = arg(args, "--threads").unwrap_or("3").parse().unwrap();
    let secs: f64 = arg(args, "--secs").unwrap_or("5").parse().unwrap();
    let batch: usize = arg(args, "--batch").unwrap_or("10000").parse().unwrap();
    let rate: u64 = arg(args, "--rate").unwrap_or("0").parse().unwrap(); // spans/s per thread, 0 = flat out
    let wal = arg(args, "--wal").map(|p| (p.to_string(), args.iter().any(|a| a == "--fsync")));
    recorder::global().ring_bytes.store(16 << 20, Ordering::Relaxed);
    let p = Pipeline::start(Config {
        path: arg(args, "--path").map(String::from),
        wal: wal.clone(),
        batch_rows: batch,
        flush_interval: Duration::from_millis(250),
        mode: InsertMode::StreamRowBinary,
        resource: resource(),
    })
    .unwrap();
    let k = Arc::new(keys());
    let log_key = recorder::intern(b"order.id");
    let t0 = Instant::now();
    let hs: Vec<_> = (0..threads)
        .map(|_| {
            let k = k.clone();
            std::thread::spawn(move || {
                let mut i = 0u64;
                let mut busy = Duration::ZERO;
                let start = Instant::now();
                while start.elapsed().as_secs_f64() < secs {
                    let b0 = Instant::now();
                    for _ in 0..256 {
                        one_span_incremental(&k, i);
                        if i % 16 == 0 {
                            recorder::log(9, b"order placed", &[(log_key, AttrVal::I64(i as i64))]);
                        }
                        i += 1;
                    }
                    busy += b0.elapsed();
                    if rate > 0 {
                        let due = Duration::from_secs_f64(i as f64 / rate as f64);
                        let el = start.elapsed();
                        if due > el {
                            std::thread::sleep(due - el);
                        }
                    }
                }
                (i, busy)
            })
        })
        .collect();
    let mut produced = 0;
    let mut busy = Duration::ZERO;
    for h in hs {
        let (n, b) = h.join().unwrap();
        produced += n;
        busy += b;
    }
    let prod_el = t0.elapsed();
    assert!(p.flush(Duration::from_secs(120)), "flush timed out");
    let el = t0.elapsed();
    let dropped = recorder::dropped_total();
    let dropped_spans = recorder::DROPPED_SPANS.load(Ordering::Relaxed);
    let traces: u64 = p.query("SELECT count() FROM otel_traces", "TabSeparated").unwrap().trim().parse().unwrap();
    let logs: u64 = p.query("SELECT count() FROM otel_logs", "TabSeparated").unwrap().trim().parse().unwrap();
    let st = &p.stats;
    let mut lat = st.batch_lat_us.lock().unwrap().clone();
    let batches = st.batches.load(Ordering::Relaxed);
    println!(
        "e2e threads={threads} batch={batch} wal={} storage={}:\n  produced {produced} spans in {:.2}s ({:.0} spans/s offered), producer cost {:.1} ns/span incl. 1/16 log\n  committed {traces} spans + {logs} logs by flush at {:.2}s -> {:.0} spans/s sustained into chDB; dropped {dropped} records ({dropped_spans} whole spans); reconcile committed+dropped_spans == produced: {}\n  drain thread {:.0} ns/record ({:.0} ns per committed span+log, excl. waits); writer {batches} batches, batch commit p50 {:.1} ms p99 {:.1} ms; insert {:.0} ms total, wal {:.0} ms total; errors {}",
        wal.as_ref().map(|w| if w.1 { "fsync" } else { "nosync" }).unwrap_or("off"),
        arg(args, "--path").unwrap_or(":memory:"),
        prod_el.as_secs_f64(),
        produced as f64 / prod_el.as_secs_f64(),
        busy.as_nanos() as f64 / produced as f64,
        el.as_secs_f64(),
        traces as f64 / el.as_secs_f64(),
        traces + dropped_spans == produced,
        st.drain_ns.load(Ordering::Relaxed) as f64 / st.drained_records.load(Ordering::Relaxed).max(1) as f64,
        st.drain_ns.load(Ordering::Relaxed) as f64 / (traces + logs).max(1) as f64,
        pct(&mut lat.clone(), 50.0) as f64 / 1000.0,
        pct(&mut lat, 99.0) as f64 / 1000.0,
        st.insert_ns.load(Ordering::Relaxed) as f64 / 1e6,
        st.wal_ns.load(Ordering::Relaxed) as f64 / 1e6,
        st.errors.load(Ordering::Relaxed),
    );
    let sample = p
        .query(
            "SELECT SpanName, count() c, round(avg(Duration)) d, any(SpanAttributes['http.response.status_code']) s FROM otel_traces GROUP BY SpanName ORDER BY c DESC FORMAT PrettyCompactMonoBlock",
            "PrettyCompactMonoBlock",
        )
        .unwrap();
    println!("{sample}");
    p.stop();
}

// ------------------------------------------------ recover: replay a WAL into a fresh engine

fn bench_recover(args: &[String]) {
    let wal = arg(args, "--wal").unwrap();
    let frames = Wal::replay(wal).unwrap();
    let expected: u64 = frames.iter().filter(|f| f.0 == 1).map(|f| f.1 as u64).sum();
    let bytes: usize = frames.iter().map(|f| f.2.len()).sum();
    drop(frames);
    let t0 = Instant::now();
    let p = Pipeline::start(Config {
        path: None,
        wal: Some((wal.to_string(), false)),
        batch_rows: 10_000,
        flush_interval: Duration::from_millis(250),
        mode: InsertMode::StreamRowBinary,
        resource: resource(),
    })
    .unwrap();
    let el = t0.elapsed();
    let got: u64 = p.query("SELECT count() FROM otel_traces", "TabSeparated").unwrap().trim().parse().unwrap();
    println!(
        "recover: replayed {:.1} MB WAL into fresh in-memory chDB in {:.2}s -> {got} spans (expected {expected}) {:.0} rows/s",
        bytes as f64 / 1e6,
        el.as_secs_f64(),
        got as f64 / el.as_secs_f64()
    );
    assert_eq!(got, expected);
    p.stop();
}

// ------------------------------------------------ otlp: JSON decode -> RowBinary -> chDB

fn bench_otlp(args: &[String]) {
    let per_req: usize = arg(args, "--spans").unwrap_or("512").parse().unwrap();
    let reqs: usize = arg(args, "--reqs").unwrap_or("400").parse().unwrap();
    let mut spans = String::new();
    let base = recorder::now_ns();
    for i in 0..per_req {
        if i > 0 {
            spans.push(',');
        }
        spans.push_str(&format!(
            r#"{{"traceId":"{:032x}","spanId":"{:016x}","parentSpanId":"{}","name":"{}","kind":2,"startTimeUnixNano":"{}","endTimeUnixNano":"{}","attributes":[{{"key":"http.route","value":{{"stringValue":"/api/orders/{{id}}"}}}},{{"key":"http.response.status_code","value":{{"intValue":"{}"}}}},{{"key":"user.tier","value":{{"stringValue":"gold"}}}}],"status":{{"code":0}}}}"#,
            i / 5 + 1,
            i + 1,
            if i % 5 == 0 { String::new() } else { format!("{:016x}", i) },
            NAMES[i & 3],
            base + i as u64 * 1000,
            base + i as u64 * 1000 + 1500,
            200 + i % 3
        ));
    }
    let body = format!(
        r#"{{"resourceSpans":[{{"resource":{{"attributes":[{{"key":"service.name","value":{{"stringValue":"checkout"}}}},{{"key":"host.name","value":{{"stringValue":"bench-host"}}}}]}},"scopeSpans":[{{"scope":{{"name":"bench","version":"0.0.1"}},"spans":[{spans}]}}]}}]}}"#
    );
    let conn = Conn::open(arg(args, "--path")).unwrap();
    conn.exec(encode::TRACES_DDL).unwrap();
    let mut out = Vec::with_capacity(1 << 20);
    let mut scratch = Vec::new();
    let mut dec_ns = 0u128;
    // receiver coalesces requests into ~10k-row inserts (per-request inserts pay chDB's fixed cost each time)
    let coalesce: usize = arg(args, "--coalesce").unwrap_or("10000").parse().unwrap();
    let t0 = Instant::now();
    let mut pending = 0;
    for i in 0..reqs {
        let d0 = Instant::now();
        pending += oscope_core::otlp_json::decode_traces(body.as_bytes(), &mut out, &mut scratch).unwrap();
        dec_ns += d0.elapsed().as_nanos();
        if pending >= coalesce || i == reqs - 1 {
            pipeline::insert(&conn, InsertMode::StreamRowBinary, 1, &out).unwrap();
            out.clear();
            pending = 0;
        }
    }
    let el = t0.elapsed();
    let got: usize = conn.query_string("SELECT count() FROM otel_traces", "TabSeparated").unwrap().trim().parse().unwrap();
    assert_eq!(got, per_req * reqs);
    let total = (per_req * reqs) as f64;
    println!(
        "otlp-json (coalesce {coalesce}): {reqs} requests x {per_req} spans ({:.0} KB body): decode {:.0} ns/span ({:.0} MB/s), decode+insert {:.0} spans/s, verified {got}",
        body.len() as f64 / 1024.0,
        dec_ns as f64 / total,
        (body.len() * reqs) as f64 / (dec_ns as f64 / 1e9) / 1e6,
        total / el.as_secs_f64()
    );
}

// ------------------------------------------------ drain: assembler cost per record, isolated

fn bench_drain(args: &[String]) {
    use oscope_core::assemble::Assembler;
    let n: usize = arg(args, "--records").unwrap_or("1000000").parse().unwrap();
    let names = [recorder::intern(b"GET /orders/{id}"), recorder::intern(b"load-order"), recorder::intern(b"priceOrder"), recorder::intern(b"db.query")];
    let (k1, k2, k3) = (recorder::intern(b"code.function"), recorder::intern(b"order.id"), recorder::intern(b"user.tier"));
    // Go-shaped K_FULL records (what osc_submit carries), 4 spans : 1 log
    let mut recs: Vec<Vec<u8>> = Vec::new();
    let base = recorder::now_ns();
    for i in 0..4096u64 {
        let mut r = vec![4u8];
        r.extend_from_slice(&(i as u128 * 0x9E37_79B9_7F4A_7C15).to_be_bytes());
        r.extend_from_slice(&(i * 7 + 1).to_le_bytes());
        r.extend_from_slice(&(if i % 4 == 0 { 0 } else { i * 7 - 6 }).to_le_bytes());
        r.extend_from_slice(&names[(i % 4) as usize].to_le_bytes());
        r.push(if i % 4 == 0 { 2 } else { 1 });
        r.push(0);
        r.extend_from_slice(&(base + i * 1000).to_le_bytes());
        r.extend_from_slice(&(base + i * 1000 + 1500).to_le_bytes());
        r.extend_from_slice(&3u32.to_le_bytes());
        r.push(1); r.extend_from_slice(&k1.to_le_bytes()); r.extend_from_slice(&10u32.to_le_bytes()); r.extend_from_slice(b"load-order");
        r.push(2); r.extend_from_slice(&k2.to_le_bytes()); r.extend_from_slice(&(i as i64).to_le_bytes());
        r.push(1); r.extend_from_slice(&k3.to_le_bytes()); r.extend_from_slice(&4u32.to_le_bytes()); r.extend_from_slice(b"gold");
        assert!(oscope_core::assemble::validate_submitted(&r));
        recs.push(r);
        if i % 4 == 3 {
            let mut l = vec![5u8];
            l.extend_from_slice(&(base + i * 1000).to_le_bytes());
            l.extend_from_slice(&(i as u128).to_be_bytes());
            l.extend_from_slice(&(i * 7 + 1).to_le_bytes());
            l.push(9);
            l.extend_from_slice(&12u32.to_le_bytes()); l.extend_from_slice(b"order served");
            l.extend_from_slice(&2u32.to_le_bytes());
            l.push(2); l.extend_from_slice(&k2.to_le_bytes()); l.extend_from_slice(&(i as i64).to_le_bytes());
            l.push(1); l.extend_from_slice(&k3.to_le_bytes()); l.extend_from_slice(&4u32.to_le_bytes()); l.extend_from_slice(b"gold");
            recs.push(l);
        }
    }
    let mut asm = Assembler::new(&resource());
    for r in &recs { asm.feed(r); } // warm
    let t0 = Instant::now();
    let mut done = 0;
    while done < n {
        for r in &recs {
            asm.feed(r);
        }
        done += recs.len();
        if asm.traces.buf.len() > 8 << 20 { asm.traces.clear(); }
        if asm.logs.buf.len() > 8 << 20 { asm.logs.clear(); }
    }
    let el = t0.elapsed();
    println!("drain: {done} records (4 spans : 1 log, 3 attrs each) -> RowBinary: {:.0} ns/record, {:.2} M records/s", el.as_nanos() as f64 / done as f64, done as f64 / el.as_secs_f64() / 1e6);
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(|s| s.as_str()) {
        Some("hot") => bench_hot(&args),
        Some("insert") => bench_insert(&args),
        Some("e2e") => bench_e2e(&args),
        Some("recover") => bench_recover(&args),
        Some("otlp") => bench_otlp(&args),
        Some("drain") => bench_drain(&args),
        _ => eprintln!("usage: bench hot|insert|e2e|recover|otlp [flags]"),
    }
}
