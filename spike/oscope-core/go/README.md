# Go + Orchestrion → in-process chDB

This directory holds an ordinary Go HTTP service, `cmd/shop`. It has no telemetry code of its own: no
tracing imports, no middleware, no span calls. Its only change is two
`//oscope:span` comments. Built with `orchestrion go build`, the binary records
the following into an embedded chDB store through `liboscope_core`:

- server spans
- client spans, with `traceparent` propagated
- function spans
- correlated logs
- spans from code the app does not own:
  - a third-party library module, `example.com/inventory`;
  - the standard library's `database/sql`, driving the `modernc.org/sqlite` driver.

It pulls in no dd-trace-go, no OTel SDK, and no collector process.

```sh
go install github.com/DataDog/orchestrion@v1.13.1
CHDB_DIR=/path/to/libchdb ./run-demo.sh
```

## How it fits together

| Piece | Role |
|---|---|
| `orchestrion.tool.go` | Opts the module in to Orchestrion. It lists only `instr`, not dd-trace-go's integrations. |
| `instr/orchestrion.yml` | The aspects: what gets rewritten, and where. |
| `instr/` | What the rewritten code calls: server and client wrappers, span helpers, slog wrappers, and start/stop. |
| `instr/dbsql/` | Aspects woven into the standard library's `database/sql`, plus the hook they call. |
| `instr/lib/` | Aspects woven into a third-party module (`example.com/inventory`), plus the hook they call. |
| `thirdparty/inventory/` | A separate module standing in for a third-party library. It has no telemetry and is not modified. |
| `oscope/` | The cgo binding to `liboscope_core`. Each span builds its attributes in a pooled Go byte buffer and crosses cgo once, at `End`, through `osc_submit`. |
| `cmd/shop` | The demo app. |
| `cmd/report` | Opens the store the app wrote and prints what was recorded. |

Aspects in `instr/orchestrion.yml`:

| Join point | Advice |
|---|---|
| `main.main` | `instr.Init()` plus `defer instr.Shutdown()`, which flushes before exit |
| `//oscope:span [span.name:x] [k:v]` on any function | A span around the body. It finds a `context.Context` or `*http.Request` argument, and its error result sets the span status. |
| `http.ListenAndServe(addr, h)` | Wraps `h` so each request gets a SERVER span, continuing any incoming `traceparent`. The span is named after the `ServeMux` pattern. |
| `(*http.Client).Do(req)` | A CLIENT span, with `traceparent` injected so the server side joins the same trace |
| `slog.InfoContext` / `slog.ErrorContext` | Also records an OTel log correlated to the current span |

Call-site aspects are scoped with `package-filter: {root: true}`, and they exclude `instr` itself.
Orchestrion also weaves the standard library. Without that scope, `net/http`'s own internal
`c.Do(...)` calls get rewritten to call `instr`, and `instr` imports
`net/http`, so the build fails with an import cycle.

The engine is configured from the environment: `OSCOPE_DB` (default `./oscope-data`),
`OSCOPE_WAL`, `OSCOPE_WAL_FSYNC=1`, and `OSCOPE_SERVICE`.

## Instrumenting code outside the app module

The app-level aspects are scoped to the root module (`package-filter: {root: true}`).
The two packages below deliberately reach further.

**A third-party module (`instr/lib`).** Every method on `*inventory.Store` that takes a
`context.Context` gets a span. The join point is an import path plus a receiver type,
so the same four lines work for any dependency:

```yaml
join-point:
  all-of:
    - import-path: example.com/inventory
    - function-body:
        function:
          - receiver: '*example.com/inventory.Store'
```

**The standard library (`instr/dbsql`).** `(*sql.DB)` and `(*sql.Tx)` `QueryContext`
and `ExecContext` get CLIENT spans carrying the SQL text, the operation, whether the
query ran in a transaction, and the driver's system name. Every other query method
(`Query`, `QueryRow`, `Exec`, `QueryRowContext`, ...) funnels into these four, so the
whole API is covered without double counting. The driver doesn't matter; the demo
uses the third-party `modernc.org/sqlite`.

Two rules make standard-library weaving work:

1. **The hook must not lead back to what it's woven into.** `database/sql` ends up
   importing `instr/dbsql`, so that package and its imports (`context`, `reflect`,
   `strings`, `sync`, and the `oscope` binding) must not import `database/sql`.
   The `net/http` aspects in `instr/` can't be woven into `net/http` itself for this
   reason, since `instr` uses `net/http`. dd-trace-go works around the same problem
   with `//go:linkname` plus `links:`.
2. **Woven code runs inside the package, with its privileges.** `*sql.Tx` has no
   `Driver()` method, so the Tx aspect reads the unexported `tx.db` field, which it
   can do because the woven code is compiled into package `sql`. That's powerful, and
   it ties the aspect to the standard library's internals, so pin the Go version the
   aspect was written against.

A successful order, from the load generator's call down into SQLite (from `cmd/report`):

```
GET                              Client     2680 us
  GET /orders/{id}               Server     1892 us
    load-order                   Internal   1295 us
    inventory.Store.Reserve      Internal    416 us   <- third-party module
      SELECT                     Client       86 us   <- database/sql, in the Tx
      UPDATE                     Client       83 us
      INSERT                     Client       23 us
    priceOrder                   Internal    158 us
```

For 2,000 requests, the store records 1,801 `inventory.Store.Reserve` spans and
1,801 `SELECT`/`UPDATE`/`INSERT` spans each, all with `db.system.name = sqlite` and
`db.in_transaction = true`. It also records the 52 setup statements outside any
transaction, with 0 orphans. The orchestrion build with SQLite takes 38 s from cold.

## What the woven code looks like

This is `loadOrder` after weaving, taken from `orchestrion go build -work`:

```go
//oscope:span span.name:load-order db.system:memory
func loadOrder(ctx context.Context, id string) (_ *order, __result__1 error) {
	{
		var __oscopeEnd func(error)
		ctx, __oscopeEnd = __orchestrion_oscopeinstr.StartSpan(ctx, "load-order", "db.system", "memory")
		defer func() { __oscopeEnd(__result__1) }()
	}
	// ... original body, with //line directives so stack traces point at main.go
```

## Measured

Measured on a 4 vCPU Xeon VM with Go 1.25, chDB 26.7.3 and orchestrion v1.13.1.

Output of `run-demo.sh` for 2,000 requests:

- **Spans:**
  - 2,001 server spans: 2,000 from the load generator plus the `/crash` request;
  - 2,000 client spans;
  - 2,001 `load-order` spans, 200 of them with status Error;
  - 1,801 `priceOrder` spans;
  - 1 load-generator root span.
- **Parent links:** every non-root span's parent is in the table, with 0 orphans:
  - 3,802 internal → server;
  - 2,001 server → client;
  - 2,000 client → internal.
- **Logs:** 2,001, each attached to its request's server span.
- **A failed order reads as one chain:** a client span `GET` (404), then the server span
  `GET /orders/{id}` (404), then `load-order` with status Error and
  `exception.message` set to "order not found".

Overhead:

| Measurement | Result |
|---|---|
| App throughput, plain `go build` vs woven, 5,000 requests × 3 runs | 4,827–5,059 req/s plain, 4,831–5,048 req/s woven: no measurable difference |
| `BenchmarkSpan`: start + 3 attributes + end, before the optimisation pass (below) | 820–850 ns/op, 72 B/op, 2 allocs/op |
| Of that, a bare cgo call | about 95 ns |
| Of that, Go-side span building (time, IDs, interning, context) | about 310 ns |
| `BenchmarkLog` | 209 ns/op, 0 allocs/op |
| DataDog symbols in the woven binary | 0 |

The optimisation pass below took this to 306 ns/span with 1 allocation.

## Hosting chDB in a Go process

**chDB's signal handlers must be off.** `oscope.Start` calls
`osc_set_engine_signal_handlers(0)` before connecting. The demo shows why.
`OSCOPE_ENGINE_SIGNALS=1 ./shop` leaves chDB's handlers installed, and then:

1. The handler with a nil-pointer bug (`/crash`) faults.
2. chDB's SIGSEGV handler catches the fault instead of the Go runtime.
3. chDB declares a fatal error and shuts the engine down. Every later insert fails with
   "server is shutting down due to a fatal error".
4. The process hangs.

With the handlers off, Go raises its normal nil-pointer panic, net/http recovers
it, and the request becomes a 500 SERVER span with an error status.

**Link order:** the cgo `LDFLAGS` put `-lchdb` first, with `--no-as-needed`, for
the load-order reason described in `../README.md`.

## Compared with exporting over the wire

`cmd/wirecost` runs one workload through each path at 5,000 requests/s for 10 s. Each request is
a SERVER span with three children (4 spans, 3 attributes each) plus one correlated
log, so each run records 200,000 spans and 50,000 logs.

`cmd/wiresink` is the receiver, a separate process on the same machine. It fully decodes
every payload, so its counts are verified and its decode cost is real, but it stores
nothing. Every run delivered exact counts with 0 decode errors. dd-trace-go ships spans only.

Numbers are CPU µs per request. App-process figures have the pacing loop's
10.8 µs/req baseline (`-mode none`) subtracted. Each run was done twice; ranges cover
both runs. Measured on a 4 vCPU VM with Go 1.25, OTel Go SDK v1.46 (logs v0.22),
dd-trace-go v2.10.1 and chDB 26.7.3. The oscope row includes storing everything in chDB.
The other rows only get data out of the process.

| Path | App CPU µs/req | Allocs/req | Alloc KB/req | GC cycles in 10 s | Caller µs/req | Wire B/req | Receiver decode µs/req |
|---|---:|---:|---:|---:|---:|---:|---:|
| **oscope in-process, incl. storage** | **27.4–27.7** | **4** | **0.20** | **5** | **3.8–4.0** | — | — |
| of which: Go recording | 4.0–4.2 | | | | | | |
| of which: Rust drain thread | 1.4–1.6 | | | | | | |
| of which: chDB (Buffer insert + merges) | 19.6–20.4 | | | | | | |
| OTLP/HTTP protobuf (OTel SDK) | 33–34 | 108 | 11.9 | 14 | 12.2–12.4 | 729 | 25–27 |
| OTLP/HTTP protobuf + gzip | 45–48 | 108 | 12.9 | 14 | 12.0–12.6 | 156 | 26–32 |
| OTLP/gRPC | 35–36 | 108 | 11.5 | 13 | 11.8–12.3 | 730 | 21–22 |
| dd-trace-go, spans only (no logs) | 43–46 | 108 | 12.4 | 53–54 | 26.5–27.4 | 1,872 | 33–38 |

What the numbers say:

- **In-process recording plus storage now costs less than exporting alone.** oscope,
  including chDB storage, uses 27.5 µs/req of app CPU. The OTel SDK uses 33–48 µs/req
  just to encode and send, before the receiver spends 21–32 µs/req decoding and then
  pays for storage on top.
- **The recording part is 6–8× cheaper than an SDK export.** It costs about 5.6 µs/req:
  about 4 in Go and 1.5 in the drain thread. It makes 4 allocations per request
  instead of 108 (one per span, none per log), which shows up as 5 GC cycles instead
  of 13–54.
- **The request goroutine pays 3–7× less:** about 3.9 µs per request inside
  instrumentation, against 12 µs for the OTel SDK and 27 µs for dd-trace-go.
- **gzip trades CPU for bytes:** 4.7× fewer bytes on the wire (729 → 156 B/req) for about
  12 µs/req more CPU. That's worth it off-box and wasted on loopback.

### The optimisation pass

What changed between the first measurement of this workload (78–92 µs/req for oscope)
and the table above (27.5):

| Change | Where | Effect |
|---|---|---|
| Drain thread parks 10–50 ms. `flush()` wakes it, and so does a producer whose ring passes half full, instead of timed polling. | `src/pipeline.rs`, `src/ring.rs` | drain 16 → 1.6 µs/req. On a VM each timed wake-up costs tens of µs; assembling a record costs 120 ns. |
| A Buffer table sits in front of each MergeTree table (`OSCOPE_BUFFER_SECS`, default 10). Inserts are queryable at once via `*_buf`, and MergeTree gets 10–30 s parts. | `src/pipeline.rs` | chDB 42–51 → about 20 µs/req, store 25 → 11 MB. The WAL covers the Buffer's crash window, and replay flushes it before removing the WAL. |
| One allocation per span: a custom context type instead of `context.WithValue`. | `oscope/oscope.go` | 2 → 1 alloc/span |
| Pre-interned `Key`s for names and attributes | `oscope/oscope.go`, `instr/*` | 426 → 306 ns/span (benchmark medians) |
| One clock read at `End` (monotonic delta) instead of two | `oscope/oscope.go` | included above |
| Striped batching of `osc_submit`: one cgo call per ~32 KiB, with a 20 ms ticker | `oscope/oscope.go` | about 45 ns/span (306 vs 353 ns direct) |

Tried and rejected:

- **LZ4 instead of ZSTD(1)** saved about 5% chDB CPU but used 58–76% more disk.
- **Bigger direct inserts without a Buffer** (up to 64k rows on a 1 s flush) cut chDB CPU to about
  30 µs/req, but data became visible later. The Buffer did better on both counts.

`go test ./oscope -bench .` benchmark medians (6 runs × 500k):

| Benchmark | Result |
|---|---|
| Span with string keys | 426 ns, 48 B/op, 1 alloc |
| Span with pre-interned keys | 306 ns |
| Span with keys, direct submit | 353 ns |
| Span with keys, parallel | 277 ns |
| Log | 166 ns, 0 allocs |

Reproduce:

```sh
go build -o wiresink ./cmd/wiresink && go build -o wirecost ./cmd/wirecost
./wiresink &
for m in none oscope otlp-http otlp-http-gzip otlp-grpc dd; do
  rm -rf wiredb; ./wirecost -mode $m -db wiredb -secs 10
done
OSCOPE_BUFFER_SECS=0 ./wirecost -mode oscope -db wiredb -batch 65536   # no Buffer, big inserts
OSCOPE_CODEC=lz4 ./wirecost -mode oscope -db wiredb                      # LZ4 instead of ZSTD
```
