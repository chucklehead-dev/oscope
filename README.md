# oscope

`oscope` is an embeddable, local-first telemetry explorer for Jolt programs.
One bounded, serializable EDN contract drives a server-rendered web interface,
a Glitter/GTK native interface, and a Glimmer native interface. Live sources
query the ClickStack-shaped OpenTelemetry tables provided by
`jolt-otel-clickhouse` in an embedded, in-process chDB database.

This repository is an initial extraction from the observability integration
demo and native UI spike. It is standalone: its manifests contain no mutable
sibling source paths and its source contains no demo namespaces.

## What works

- spans, logs, and metrics distribution queries through a closed allowlist;
- a ClickStack-style trace workbench with bounded service, operation, status,
  duration, and time filters, complete parent/child span trees, span events,
  and trace-correlated logs;
- 15 minute, 1 hour, 6 hour, and 24 hour bounded windows;
- a semantic accessible table and a validated
  [Plotje](https://github.com/scicloj/plotje)-compatible chart spec;
- portable spec-to-SVG rendering for line, point, bar, area, rule, and tick
  marks, with bounded palettes, colors, opacity, sizing, and grid options;
- mountable Plotje and safe-Hiccup editors with bounded, versioned edit
  documents, an in-page grammar reference and loadable examples, a
  server-rendered fallback, and progressive previews;
- a standalone loopback OTLP/HTTP JSON receiver and viewer using one process,
  one connection, and one schema owner;
- static-first, high-contrast, responsive Ring UIs: the trace workbench at
  `/oscope/telemetry` and aggregate/chart explorer at `/oscope`;
- an opt-in live web mode with bounded refresh, stale-response rejection, and
  an exact Freeze-for-export snapshot while preserving the static default;
- raw Arrow and Parquet downloads for spans, logs, gauges, sums, and
  histograms through a closed export contract;
- Glitter and Glimmer adapters consuming exactly the same screen model;
- caller-owned collector connections and oscope-owned connections with
  explicit, idempotent retirement; and
- deterministic headless tests plus an opt-in real-chDB lifecycle gate.

The distribution query plan contains no SQL. User input selects only signal,
field, time window, and result limit values from closed sets. Raw export is a
separate versioned data-only command: oscope maps its closed signal and metric
kind choices to one of five physical tables, then generates a parameterized
`SELECT`. Export requests cannot supply SQL, table or column names, filesystem
paths, or filenames.

## Run the standalone receiver and viewer

Install or point Jolt at `libchdb.so`, then start the persistent local
collector. This command form works in fish as well as POSIX shells:

```sh
cd oscope
env JOLT_CHDB_LIB=/path/to/libchdb.so jolt -M:server
```

Oscope listens only on `127.0.0.1:4318`, stores data in
`chdb:./oscope-data`, receives OTLP/HTTP JSON at `/v1/traces`, `/v1/logs`, and
`/v1/metrics`, and serves the trace workbench at
<http://127.0.0.1:4318/oscope/telemetry>. `/` redirects to that workbench;
the aggregate logs/metrics/chart explorer remains at
<http://127.0.0.1:4318/oscope>, `/healthz` reports process health, and
`/oscope/export` serves bounded Arrow or Parquet downloads.
The viewer's **Edit this chart** link opens `/oscope/edit/plotje` with the
current bounded query selection; `/oscope/edit/hiccup` provides the companion
data-only Hiccup surface. Both editors remain functional when JavaScript is
disabled. The Plotje editor documents the supported grammar and includes
loadable bar and layered area/point/rule examples; its JavaScript only adds
debounced preview and one-click example loading.

Override the port or database without shell-specific `export` syntax:

```sh
env JOLT_CHDB_LIB=/path/to/libchdb.so \
    OSCOPE_PORT=14318 \
    OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/oscope-data \
    jolt -M:server
```

`OSCOPE_HOST` is accepted only as `127.0.0.1`; jolt-http's current transport
bind is intentionally loopback-only. The receiver accepts uncompressed
`application/json`, caps the consumed request body at 1 MiB, and admits one
OTLP export at a time because every signal shares the embedded connection.

This process does not initialize an OTel SDK and does not wrap its HTTP routes
with tracing or logging middleware. Viewer, health, export, and receiver
traffic therefore cannot feed telemetry back into the collector. Shutdown is
retry-safe and ordered: stop ingress, retire the oscope source, close the span,
log, and metric exporter faces, then close the shared connection.

### Emit a sample workload

With the receiver running, use a second terminal to run the companion Jolt
application:

```sh
cd oscope
jolt -M:emit-sample
```

Each invocation emits a new five-span checkout trace with correct parentage,
three trace-correlated logs, and one counter, gauge, and histogram sample. It
uses the public `otel.sdk`, tracing, logging, and metrics APIs rather than
posting fixture JSON. Re-run it to exercise live updates, then open
<http://127.0.0.1:4318/oscope>.

The standard OTel endpoint variable works in fish and POSIX shells, or the base
URL may be supplied as the only argument:

```sh
env OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 jolt -M:emit-sample
jolt -M:emit-sample http://127.0.0.1:14318
```

The emitter checks `/healthz` before creating telemetry and exits visibly when
the local receiver is unavailable. Its own HTTP export calls are not woven with
instrumentation, so running it cannot create a collector feedback loop. New
traces and correlated logs appear in the workbench on its bounded two-second
poll; hidden tabs stop polling. Trace links and detail pages still work when
JavaScript is disabled.

## Run or embed only the web version

Render a deterministic, self-contained HTML snapshot:

```sh
cd oscope
jolt -M:web-snapshot > oscope.html
```

For a live database snapshot:

```sh
export JOLT_CHDB_LIB=/path/to/libchdb.so
export OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/telemetry
jolt -M:live-web > oscope.html
```

The functional web UI is an embeddable Ring adapter rather than a second HTTP
stack. Mount it in an existing collector or application:

```clojure
(require '[oscope.live :as oscope]
         '[oscope.ui.web :as oscope-web])

(def source (oscope/open! {:db-spec "chdb:/absolute/path/to/telemetry"}))
(def oscope-handler
  (oscope-web/handler
   source
   {:path "/admin/telemetry"
    :visualization-editor-path "/admin/telemetry/edit/plotje"}))

;; Compose normally; the adapter returns nil for routes it does not own.
(defn app [request]
  (or (oscope-handler request)
      {:status 404 :headers {} :body "not found"}))

;; At application shutdown:
(oscope/close! source)
```

The visualization editor is independently mountable. Its Plotje page queries
the selected canonical screen once to seed a versioned edit document; preview
and fallback POSTs only parse and render the submitted bounded document:

```clojure
(require '[oscope.ui.visualization-editor :as editor])

(def editor-handler
  (editor/handler
   source
   {:path "/admin/telemetry/edit"
    :viewer-path "/admin/telemetry"
    ;; An instrumented host supplies its generic context wrapper here.
    :run-suppressed (fn [thunk] (thunk))}))
```

Compose `editor-handler` like any other Ring adapter; it returns `nil` for
paths it does not own. `:run-suppressed` receives a zero-argument thunk for
every owned editor route. The standalone collector needs no wrapper because it
does not initialize an SDK or install telemetry middleware. An instrumented
host should provide the same generic suppression context it uses for its
viewer and telemetry-storage routes. Encoded editor bodies are capped at
40,000 bytes; Plotje source is capped at 32,768 characters and safe-Hiccup at
16,384 characters before any document can reach a renderer.

The configured mount path drives both Ring routing and form navigation. Every
control is an ordinary GET form control, so querying, charts, tables, and
navigation work with JavaScript disabled. The page ships a restrictive CSP and
no script element; a host application can add progressive enhancement outside
the adapter contract.

### Raw data downloads

A live page includes an ordinary no-JavaScript export form. Its download route
is derived from the mount path: `/oscope/export` by default and, for the example
above, `/admin/telemetry/export`. The form requires an absolute half-open
`[start-unix-nano, end-unix-nano)` window no longer than 24 hours, explicitly
selects gauge, sum, or histogram for metrics, and can only lower these hard
limits:

- 100,000 physical rows;
- 64 MiB of encoded output; and
- Arrow file or Parquet output.

The row limit truncates the ordered physical-row selection; it is a bound, not
a pagination cursor. These are **result bounds**, not execution-cost, rows-read,
or wall-time bounds: ClickHouse may scan and sort more physical rows before it
produces the bounded result. Hosts that expose large or untrusted datasets
should also configure engine-side resource limits.

One live source admits one export at a time by default. Its permit remains held
until jolt-http finishes writing the response body or the write fails, so slow
clients cannot accumulate multiple maximum-sized byte arrays after native
queries complete. Additional requests receive `503` with `Retry-After`; an
embedding application may choose a small capacity up to 16 with
`:export-capacity`, accounting for the corresponding memory exposure.

Oscope supplies the MIME type and a filename made only
from closed source names, epoch integers, and the selected extension. It never
writes a server-side export path. The returned byte array is copied by
`jolt-chdb` before the native query result is destroyed, so the Ring response
does not retain a libclickhouse buffer.

The default adapter rejects browser requests marked `Sec-Fetch-Site:
cross-site`. A host may supply `:authorize-export?` for stronger policy. The
standalone composition is loopback-only; any future remote exposure must add
an authenticating reverse proxy or an equivalent host authorization hook.

The deterministic sample page renders the same controls disabled and its
export route returns 404. It never manufactures a data file when no live
exporter exists.

## Run the native versions

GTK4 and a working display/WSLg are required.

```sh
# Deterministic Glitter UI
jolt -M:native

# Live Glitter UI
env JOLT_CHDB_LIB=/path/to/libchdb.so \
    OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/telemetry \
    jolt -M:live-native

# Standalone OTLP/HTTP receiver plus the native viewer, sharing one connection
env JOLT_CHDB_LIB=/path/to/libchdb.so \
    OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/telemetry \
    jolt -M:native-server

# Alternative Glimmer/GTK adapter
jolt -M:glimmer-native
```

`-M:live-glimmer-native` is the live Glimmer equivalent. The shared model is
compatible with Glimmer plus `glimmer-uikit` on macOS, but this Linux checkout
cannot execute the AppKit backend. Glitter is the default GTK architecture;
Glimmer remains useful for components that benefit from local reactive state.

The native server receives OTLP/HTTP on `127.0.0.1:4318` and also retains the
web viewer at `http://127.0.0.1:4318/oscope`. Its Glitter window renders the
canonical `screen[:chart]` through oscope's bounded Plotje-to-SVG renderer and
GtkPicture; the accessible distribution remains below the chart. Each window
owns and removes its temporary SVG files. For an opt-in WSLg smoke that closes
itself, set `OSCOPE_NATIVE_AUTO_QUIT_MS` to a positive millisecond count.

Current Glitter and Glimmer application runners own their mounted root and do
not return a complete unmount handle. Each oscope adapter isolates its model
and callbacks per instance; its logical `close!` rejects future selections,
but the toolkit retains the mounted root and callbacks until window teardown.
A future runner API returning that root can add explicit unmount and release
without changing the oscope contract. Applications that require independently
owned embedded native windows should treat that runner enhancement as a gate.

## Share a collector connection

An in-process OTLP collector should share its existing connection:

```clojure
(def source (oscope/open! {:connection collector-connection}))
```

`oscope/close!` retires queries but never closes a caller-owned connection. If
`:db-spec` is supplied instead, oscope opens and closes the connection itself.
Both paths run the existing exporter schema check by default; oscope does not
fork or own a competing schema. The standalone composition lets the exporter
perform the sole schema migration and opens the shared oscope source with its
redundant check disabled.

An embedded chDB path should normally have one process-level owner. Coordinate
ingest and query access through that owner rather than opening the same physical
database independently in multiple processes.

## Contract

The dependency direction is deliberately one-way:

```text
selection -> versioned command -> effect -> bounded query -> screen -> views
                  |
raw selection -> export command -> closed SQL -> query-bytes -> Ring download
                  |
screen chart -> versioned visualization document -> safe preview renderer
```

- `oscope.query` validates selection and builds exact SQL-free plans.
- `oscope.telemetry` owns the bounded parameterized trace list, detail,
  correlated-log, filter-option, and span-tree contracts.
- `oscope.raw-export` validates absolute windows, source and format choices,
  caps, generated parameterized SQL, the complete owned-byte result envelope,
  MIME type, and suggested filename.
- `oscope.command` is the versioned, portable intent envelope.
- `oscope.effect` builds and validates a complete screen before one atomic
  replacement; renderers never mutate individual result fields.
- `oscope.view-model` is serializable EDN with semantic controls, exact plan
  provenance, a Plotje-compatible chart, and accessible table rows.
- `oscope.plotje.spec` and `oscope.plotje.svg` are the bounded portable chart
  dependency. The supported subset currently includes line, point, bar, area,
  rule, and tick marks plus explicit safe style options; it is not the full
  Plotje API.
- `oscope.visualization.document` owns the closed Plotje/Hiccup edit envelope;
  `oscope.hiccup.spec` rejects active tags, URLs, and event attributes; and
  `oscope.ui.visualization-editor` is the mountable Ring surface.
- `oscope.live` is the only owned/shared chDB lifecycle boundary and owns the
  source-wide export admission state.
- `oscope.ui.workbench` and `oscope.ui.web` are sibling mountable Ring
  surfaces over the same connection: detailed traces/logs and aggregate
  logs/metrics/charts respectively. A host may supply `:advise-trace` to the
  workbench so library-specific Kindly metadata is applied only at render time.

The first implementation is synchronous. Before moving database queries onto
GUI workers, retain monotonically increasing request IDs and reject stale
completions before the whole-screen mutation.

## Tests

The full deterministic suite does not open a native window or a real chDB, but
it does load the Glitter/Glimmer adapter namespaces. It exercises
query/model/command invariants, Plotje SVG, the Ring adapter, headless Glitter
reconciliation, Glimmer/Glitter instance isolation, bounded visualization
documents and editor routes, OTLP body policy, route
composition, shared-connection ownership, and retry-safe shutdown:

```sh
jolt -M:test
```

Hosted CI intentionally runs the portable collector, query, export, and web
layers without loading the GTK-facing Glitter/Glimmer namespaces:

```sh
jolt -M:test-headless
```

That narrower gate supplements rather than replaces `-M:test`; native adapter,
real chDB, independent-reader, and standalone receiver coverage remain explicit
local/release gates below.

Run the real embedded database gate separately. It starts a real loopback
server, ingests spans, logs, and metrics through OTLP/HTTP JSON, queries the
canonical live source, renders the viewer, downloads Parquet, proves viewer
traffic does not change telemetry counts, and closes the lifecycle twice:

```sh
env JOLT_CHDB_LIB=/path/to/libchdb.so \
  jolt -M:test-chdb
```

When `clickhouse-local` is installed, independently parse both encoded formats
and prove half-open boundary and row-truncation semantics with:

```sh
env JOLT_CHDB_LIB=/path/to/libchdb.so \
  jolt -M:test-readers
```

Self-contained receiver builds require Jolt v0.7.28 or newer. Jolt v0.7.27 can
run `jolt -M:server` from source, but its app builder may incorrectly inherit
`jolt.ffi` from the compiler image and produce a binary with an unbound
`jolt.ffi/errno`. The standalone smoke builds and starts the real receiver long
enough to reject that artifact class:

```sh
env JOLT_CHDB_LIB=/path/to/libchdb.so \
    JOLT_BIN=/path/to/jolt-v0.7.28-or-newer \
    JOLT_TOOLCHAIN=/path/to/jolt-with-chez-10.4.1 \
    test/standalone_build_smoke.sh
```

## Exact dependency baselines

- `chucklehead-dev/jolt-otel-clickhouse` `c1d4aad8188811258dda7d777808649255b13cbc`
- `chucklehead-dev/jolt-chdb` `fffaf33208d9404ff4f8e48ecf6d8f9ca03a62c3`
- `casselc/jolt-http` `0629087f4d7e42343164e43906fae6d707787ed0`
- `burinc/glitter` `482642fd3c9671b05f0ffaa2ef47420b1a92553b`
- `casselc/glimmer` `6dab5597dc0d912793fe175d0d3cbb9e75f11426`
- `jolt-lang/glimmer-gtk` `ce79d45698d36ccf496397bb85974e3cce6abfd8`
- `casselc/data.json` `8a6dc9668e5c3596a335759defeb7ec80cd3b5f8`

The pinned ClickHouse exporter owns the required Jolt DB bootstrap at its
public explorer entrypoint. A clean oscope consumer therefore needs no hidden
load-order require and cannot accidentally compile `jdbc.core` before the
`ResultSet` compatibility model exists.

No dependency on the source demo or native spike remains. No upstream pull
request is required to build or test this repository.

## Near-term work

- add explicit export pagination or partition manifests for workflows that
  need more than one bounded physical-row download; and
- add a preserve-to-table command after the frozen-screen contract has a
  caller-owned destination/schema policy.

Copyright contributors. Distributed under the Eclipse Public License 2.0.
