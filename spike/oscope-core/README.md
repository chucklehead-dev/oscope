# oscope-core spike

The design this spike measures is in [design.html](design.html). This directory is standalone Rust; nothing in the Jolt build or tests uses it.
[go/](go/README.md) is a Go app instrumented at compile time with Orchestrion,
recording into the same in-process store through the C ABI.

A throwaway Rust spike that measures the proposed design against chDB 26.7.3. It covers:

- the per-thread ring recorder
- the C ABI
- RowBinary encoding
- `chdb_stream_insert`
- a binary WAL with replay
- OTLP/HTTP JSON decoding

## Build

```sh
# libchdb 26.7.3 (same release jolt-chdb pins)
curl -sSL -o libchdb.tar.gz https://github.com/chdb-io/chdb-core/releases/download/v26.7.3/linux-x86_64-libchdb.tar.gz
mkdir -p ../chdb && tar xzf libchdb.tar.gz -C ../chdb
CHDB_DIR=$PWD/../chdb cargo build --release
```

## Benchmarks

```sh
B=target/release/bench
$B hot --api incremental --threads 4          # producer cost, allocations
$B hot --api record --threads 4
$B insert --rows 200000                        # JSONEachRow vs RowBinary, batch 512 / 10000
$B insert --rows 200000 --path ./db --wal w.wal  # on disk, plus fsync'd WAL
$B insert --rows 200000 --batches 64,512 --fmt rb-stream-buffer
$B e2e --threads 2 --secs 10 --rate 60000      # producers -> rings -> drain -> chDB
$B e2e --threads 2 --secs 10 --rate 50000 --path ./db --wal d.wal --fsync
$B recover --wal d.wal                         # fresh process, replay WAL
$B otlp --spans 512 --reqs 400 --coalesce 10000
```

## Storage layout

Each MergeTree table (`otel_traces`, `otel_logs`) has a Buffer table in front of
it (`otel_traces_buf`, `otel_logs_buf`). Rows are queryable through the `_buf`
table as soon as they are inserted, and the Buffer writes 10–30 s chunks into
MergeTree. At 25k rows/s that halved chDB CPU and on-disk size compared with
direct 8k-row inserts. `osc_flush` forces the Buffer out, so after a flush the
MergeTree tables are complete. Live queries that want the newest rows should read
the `_buf` tables. `OSCOPE_BUFFER_SECS` sets the interval (default 10); 0 turns the
Buffer off.

## `osc_submit`

Hosts whose FFI calls are expensive, or which can't pass pointers inside
structs (cgo, JVM FFM, jolt.ffi), can encode finished spans and logs
themselves in the ring wire format documented in `include/oscope.h`. They pass
a flat buffer with one call. Every record is bounds-checked before any are
accepted.

## Embedding from other languages

The C example must be linked with libchdb first. When libchdb is only a
transitive startup dependency, its static initialisers recurse and the
process crashes before `main`. Loading it with `dlopen` (what ctypes,
jolt.ffi, JNA and similar do) works without any special handling.

```sh
cc -O2 -Iinclude examples/hello.c -Wl,--no-as-needed -L../chdb -lchdb -Wl,--as-needed \
   -Ltarget/release -loscope_core -Wl,-rpath,$PWD/target/release -Wl,-rpath,$PWD/../chdb -lpthread -o hello
cc -O2 -Iinclude examples/dl.c -ldl -Wl,-rpath,$PWD/target/release -Wl,-rpath,$PWD/../chdb -o dl
python3 examples/py_ctypes.py
```

## Results

Measured 2026-09-23 on a 4 vCPU Intel Xeon @ 2.10GHz VM with 15 GB RAM,
chDB 26.7.3 and Rust 1.94.1. Each figure is from a single run.

| Measurement | Result |
|---|---|
| Hot path, incremental API (start + 3 attrs + end) | 115 ns/span on 1 thread, 117 ns/span on 4 threads, 0 allocations |
| Hot path, one-shot `record_span` | 58 ns/span on 1 thread, 62 ns/span on 4 threads (65 M spans/s aggregate), 0 allocations |
| `clock_gettime` on this VM | 28 ns per call. The incremental API calls it twice per span. |
| Insert, 512-row batches, in memory | JSONEachRow 61k rows/s; RowBinary stream 60k rows/s |
| Insert, 512-row batches, on disk | RowBinary 62k rows/s; RowBinary + fsync WAL 66k rows/s |
| Insert, 512-row batches through a Buffer table | 107k rows/s, p50 4.1 ms |
| Insert, 10k-row batches, in memory | JSONEachRow 110k rows/s; RowBinary 204k rows/s |
| Insert, 10k-row batches, on disk + fsync WAL | 178k rows/s |
| End to end, 2 producers at 120k spans/s offered | 0 dropped; committed within 60 ms of the producers stopping |
| End to end, overloaded (15 M spans/s offered) | 192k spans/s plus logs sustained; whole spans shed; committed + dropped reconciles exactly |
| Durable end to end on disk with per-batch fsync, 100k spans/s | 1,000,448 spans, 0 dropped |
| Recovery: replaying a 269 MB WAL in a fresh process | 184k rows/s, exact count |
| OTLP/HTTP JSON decode | 2.2 µs/span (195 MB/s) on one core |
| OTLP decode + insert, coalesced to 10k rows | 138k spans/s |
| Python via ctypes (4 calls per span) | 2.7 µs/span, dominated by ctypes overhead |

Findings:

- Putting binary data inline after `FORMAT RowBinary\n` in `chdb_query_n` is
  unsafe. The parser skips leading whitespace, so a batch whose first byte is
  0x09, 0x0A, 0x0D or 0x20 misaligns. Use `chdb_stream_insert`.
- The `ThreadStatus` corruption that jolt-chdb reports for long-running stream
  inserts did not reproduce here. Several hundred stream inserts ran per
  process on one dedicated writer thread without error.
- libchdb.so is 566 MB (already stripped) and 174 MB as a tarball. The Rust
  core is 5.8 MB.
