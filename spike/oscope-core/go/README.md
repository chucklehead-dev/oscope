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
