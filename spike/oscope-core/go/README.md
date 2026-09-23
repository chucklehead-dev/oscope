# Go + Orchestrion → in-process chDB

This directory holds an ordinary Go HTTP service, `cmd/shop`. It has no telemetry code of its own: no
tracing imports, no middleware, no span calls. Its only change is two
`//oscope:span` comments. Built with `orchestrion go build`, the binary records
the following into an embedded chDB store through `liboscope_core`:

- server spans
- client spans, with `traceparent` propagated
- function spans
- correlated logs

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
| `BenchmarkSpan`: start + 3 attributes + end | 820–850 ns/op, 72 B/op, 2 allocs/op |
| Of that, a bare cgo call | about 95 ns |
| Of that, Go-side span building (time, IDs, interning, context) | about 310 ns |
| `BenchmarkLog` | 209 ns/op, 0 allocs/op |
| DataDog symbols in the woven binary | 0 |

The two allocations are `context.WithValue`. The obvious next optimisation is
to have the aspect intern span names and keys at package init, which removes the
`sync.Map` lookups.

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
10.7 µs/req baseline (`-mode none`) subtracted. Each run was done twice; ranges cover
both runs. Measured on a 4 vCPU VM with Go 1.25, OTel Go SDK v1.46 (logs v0.22),
dd-trace-go v2.10.1 and chDB 26.7.3.

**App process: recording and getting the data out**

| Path | CPU µs/req | Allocs/req | Alloc KB/req | GC cycles in 10 s | Wire B/req |
|---|---:|---:|---:|---:|---:|
| oscope in-process: Go recording | ~4 | 8 | 0.28 | 5 | — |
| oscope in-process: Rust drain thread | 10–11 | 0 | 0 | — | — |
| OTLP/HTTP protobuf (OTel SDK) | 34 | 108 | 11.9 | 15 | 729 |
| OTLP/HTTP protobuf + gzip | 46 | 108 | 12.9 | 14 | 156 |
| OTLP/gRPC | 33–35 | 108 | 11.4 | 13 | 730 |
| dd-trace-go, spans only (no logs) | 42–43 | 108 | 12.5 | 53–54 | 1,871 |

**Storage, which somebody has to pay for**

| Where | CPU µs/req |
|---|---:|
| Receiver: decode only, before any storage (OTLP HTTP / gRPC / dd msgpack) | 24–27 / 20–21 / 28–30 |
| chDB insert and merge in-process, 8k-row batches | 51 |
| chDB insert and merge in-process, 64k-row batches | 18–19 |

What the numbers say:

- **Recording is 2.5–3× cheaper in-process.** oscope spends about 14 µs/req (Go 4 + drain 10)
  against 33–46 µs/req for the OTel SDK and 42 µs/req for dd-trace-go, which also sends no logs.
  It also makes 8 allocations per request instead of 108, which shows up as 5 GC cycles
  instead of 13–54.
- **Storage moves into the app.** With an external receiver, the app pays only for
  encoding. Storage, plus another 20–30 µs/req of decoding, lands on the receiver.
  In-process, chDB's insert and merge CPU runs inside the app. At 5,000 req/s with 8k-row
  batches that's about 25% of one core, and 64k-row batches cut it to about 10%.
- **Totals across both processes:**
  - Wire: app 34 + decode 20–27 + storage ≈ 75–110 µs/req.
  - In-process: 14 + storage ≈ 33–65 µs/req.
  - The same chDB storage cost is assumed on both sides. The receiver-side storage
    wasn't measured, because the sink stores nothing.
- **Batch size is the biggest lever on total cost:** 8k → 64k rows took in-process storage
  from 51 to 19 µs/req. The trade-off is that data becomes visible later.
- **gzip cuts wire bytes by 4.7× (729 → 156 B/req) for about 12 µs/req more CPU.** That's worth
  it off-box and wasted on loopback.

Caller-side wall time per request (the time the request goroutine spends inside
instrumentation) was also recorded. It is noisy on a 4-core box shared with background
threads and the receiver: oscope 4.9–10.9 µs, OTLP 11.3–12.2 µs, dd-trace-go 25–27 µs. The
isolated microbenchmark for oscope is about 0.85 µs per span.

Reproduce:

```sh
go build -o wiresink ./cmd/wiresink && go build -o wirecost ./cmd/wirecost
./wiresink &
for m in none oscope otlp-http otlp-http-gzip otlp-grpc dd; do
  rm -rf wiredb; ./wirecost -mode $m -db wiredb -secs 10
done
./wirecost -mode oscope -db wiredb -batch 65536 -flushms 5000   # bigger batches
```
