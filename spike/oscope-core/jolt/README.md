# Jolt → in-process chDB

This is a Jolt binding to `liboscope_core`, written directly against the C ABI with
stock Jolt v0.8.6 and `jolt.ffi`. It uses no OTel SDK and no exporter, and doesn't
depend on jolt-chdb or jolt-otel-clickhouse. It includes a simulated LLM agent
instrumented the way samizdat instruments itself.

```sh
curl -fsSL https://raw.githubusercontent.com/jolt-lang/jolt/v0.8.6/install | bash -s -- --version 0.8.6
CHDB_DIR=/path/to/libchdb ./run-demo.sh
```

| Piece | What it is |
|---|---|
| `src/oscope/core.clj` | The binding: `start!`, `flush!`, `stop!`, `key`/`defkeys`, `with-span`, `attr!`, `record-error!`, `log!` and `query`. |
| `src/agent/demo.clj` | Agent sessions → turns → `model.chat` generations and tool calls on 4 threads, using the GenAI and `langfuse.*` attributes from samizdat's manifest. It then prints Langfuse-style views from the store. |
| `src/agent/bench.clj` | Per-operation cost, plus the fixed-rate workload from `../go/cmd/wirecost`. |
| `src/oscope/check.clj` | End-to-end checks of the span guard against a real store. |

## Using it

```clojure
(require '[oscope.core :as o])

(o/defkeys n-chat "model.chat" k-model "gen_ai.request.model" k-out "gen_ai.usage.output_tokens")

(o/start! {:db "./oscope-data" :service "my-agent"})

(o/with-span [s n-chat :client k-model "claude-sonnet-5"]
  (let [reply (call-model)]
    (o/attr! s k-out (:output-tokens reply))
    (o/log! :info "model replied")      ; correlated with this span
    reply))

(o/flush!)
(println (o/query "SELECT SpanName, count() FROM otel_traces GROUP BY SpanName" "PrettyCompact"))
```

Span names and attribute keys are interned once, with `defkeys` at load time; hot
calls then pass small integers. Parentage comes from the recorder's per-OS-thread span
stack, so no context value has to be passed around. The consequence is that a span must
start and end on the same OS thread, with nothing parked in between. Code that parks a
fiber inside a span would need explicit parents (`osc_span_record`), which this binding
doesn't expose yet.

## What the demo records

2,000 sessions on 4 threads take 334–399 ms, with 0 dropped records. The store then answers
Langfuse-style questions directly:

```
Generations by model (Langfuse 'generation' observations)
   model            generations  input_tokens  output_tokens  p50_us
   claude-haiku-4-5        2323       6055733         564096     134
   claude-sonnet-5         2322       5949963         557608     133
   claude-opus-5-5         2254       5772009         536008     132

Tool calls
   tool       calls  errors  sample_error
   run_tests   1241     249  2 tests failed

One session, as a trace
   agent.run                      856 us  claude-sonnet-5
     agent.turn                   305 us
       model.chat                 149 us  out_tokens 378
       tool          Error        101 us  run_tests: 2 tests failed
     agent.turn                   149 us
       ...
```

Every log (2,249) is attached to a span, and there are 0 orphan spans.

## What it costs

Measured on the 4 vCPU VM used for the Go and Rust numbers, with stock Jolt v0.8.6.
Heap is measured the way jolt-otel-clickhouse's benchmarks measure it:
Δ(`bytes-allocated` + `gc-bytes`). Every phase checked its row counts in chDB exactly,
and nothing was dropped.

| Measurement | Result |
|---|---|
| Span with 3 attributes, on the recording thread | 485–740 ns across four runs (noisy on a shared 4-core VM), 207 B heap |
| Span with 2 literal attributes + 1 runtime attribute (`attr!`) | 553–582 ns, 223 B heap |
| Log with 1 attribute | 267–316 ns, 176 B heap |
| Fixed-rate workload (5,000 req/s × 10 s; 4 spans + 1 log per request), whole-process CPU net of the pacing loop, including the drain thread and chDB storage | **29.0–29.8 µs/req** |
| Same workload, heap and GC | 2.2 KB heap per request (about 450 B per span or log), 7 GCs in 10 s |

For comparison, on the same VM and workload:

- the Go binding: 27.5 µs/req;
- the OTel Go SDK: 33–48 µs/req just to export over OTLP, before any receiver work.

jolt-otel-clickhouse's own baseline was about 1,000 items/s with about 700 KB of Scheme
heap per item. That was measured on an i7 under WSL2 with a different workload, so it
isn't directly comparable, but the heap gap is more than 1,000×.

### What the measurements changed in the binding

| Finding | Change |
|---|---|
| A `jolt.ffi` call costs about 14 ns, so every C call is cheap. | Unlike Go, there is no batching layer: `with-span` calls the incremental C API directly. |
| `.getBytes` and `ThreadLocal.get` cost about 315 ns each, and `ffi/write-bytes` about 1 µs. | Strings go through `defcfn`'s `:string` type, which Chez converts natively (about 60 ns, 32 B). The C ABI gained NUL-terminated variants for this: `osc_span_attr_cstr`, `osc_intern_cstr`, `osc_log_cstr3` and `osc_start_cstr`. |
| A `try`/`catch` costs about 300 B of heap on every entry, even when nothing throws. `try`/`finally` costs about 100 B. | `with-span` guards with `finally` plus the new `osc_span_abort`, which ends the span as Error only if it is still open. Span heap fell from 483 B to 207 B. The trade-off is that an escaping exception is recorded as Error with `exception.escaped=true` but without its message. Code that catches can call `record-error!` to keep it. |
| A Jolt wrapper function around each foreign call costs about 60 ns per span. | The macro calls the `defcfn`s directly. |
| `:string` arguments are not allowed on `:blocking` (collect-safe) calls. | `query` is not `:blocking`. |

The earlier probes that suggested about 160 ns per span ran with no pipeline started. The
ring filled after 1 MiB and they were timing the cheaper drop path. The numbers above
record every span.

## Checks

`jolt -M:check` runs five end-to-end checks against a real store:

- an exception escaping a child ends it as Error with `exception.escaped=true`;
- the next sibling is still parented to the parent, not to the abandoned child;
- the parent that caught the exception ends Unset;
- `record-error!` keeps the message and records the span once;
- an exception escaping the outermost span still closes it.
