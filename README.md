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
- reusable metric-series recipes with fixed time buckets, service/unit/scope/
  environment dimensions, and named count/sum/min/max/avg/p50/p95/p99 fields;
- reset-aware cumulative monotonic Sum recipes with exact observed increase,
  per-second rate, stored provenance, interval duration, and reset counts;
- a ClickStack-style trace workbench with bounded service, operation, status,
  duration, and time filters, complete parent/child span trees, span events,
  and trace-correlated logs;
- a raw event explorer for bounded log-body/severity and metric-name/kind
  searches, with trace links from correlated log records;
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

The distribution, metric-series, and counter-series query plans contain no SQL. User input
selects only values from closed sets plus an exact bounded metric name. The
shared exporter library maps those recipes to parameterized queries; callers
cannot supply identifiers, aggregate functions, or expressions. Raw export is
a separate versioned data-only command: oscope maps its closed signal and
metric-kind choices to one of five physical tables, then generates a
parameterized `SELECT`. Export requests cannot supply SQL, table or column
names, filesystem paths, or filenames.

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
<http://127.0.0.1:4318/oscope>, and raw log/metric rows are available at
<http://127.0.0.1:4318/oscope/events>. `/healthz` reports process health, and
`/oscope/export` serves bounded Arrow or Parquet downloads.
The viewer's **Edit this chart** link opens `/oscope/edit/plotje` with the
current bounded query selection; `/oscope/edit/hiccup` provides the companion
data-only Hiccup surface. Both editors remain functional when JavaScript is
disabled. The Plotje editor documents the supported grammar and includes
loadable bar and layered area/point/rule examples; its JavaScript only adds
debounced preview and one-click example loading. Its editable chart contains a
bounded `:telemetry-query` with grouping, equality filters, and named
count/sum/average/min/max/percentile series instead of copying returned
telemetry rows into the spec, so it can be reused as values change.
Reusable expressions can group those results into Unix-epoch-aligned `:1m`,
`:5m`, `:15m`, or `:1h` buckets (or `:none`) and expose the bucket start as a
named chart column.
See [Reusable Plotje charts](docs/PLOTJE.md) for the current contract and the
bounded aggregate/query grammar.
The reusable grammar also supports bounded server-side `:add`, `:subtract`,
`:multiply`, and `:divide` calculations over named aggregate series, with
explicit null and division-by-zero behavior. Per-series equality filters allow
ratios such as error count divided by total request count while global filters
continue to constrain every series.
Plotje compile and execution can also emit an opt-in, privacy-shaped semantic
lifecycle to a caller-owned sink for offline Hegel validation. The bounded
events contain closed grammar metadata and outcomes, never filter values, SQL,
result rows, credentials, user aliases, or exception details; see
[Reusable Plotje charts](docs/PLOTJE.md#redacted-query-validation-events).

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

### Storage modes and recovery guarantees

The standalone server's default `chdb:./oscope-data` setting provides
**local path persistence**. Oscope closes its embedded connection during orderly
shutdown, and the real-chDB integration test
[`standalone-persistent-restart-retains-telemetry`](test/oscope/server_integration_test.clj)
proves that telemetry remains queryable after the server reopens the same local
directory. This is a useful local restart guarantee, but it depends on that
directory remaining available on the same filesystem.

Local path persistence does not provide object-storage recovery, cross-machine
restore, a portable checkpoint plus write-ahead log (WAL), single-writer
leases or fencing, checkpoint/WAL integrity verification, or an acknowledged
remote durability boundary. The current exporter shutdown and connection
close are not chDB Durable `close()` operations.

| Mode | Oscope configuration | Status and recovery boundary |
| --- | --- | --- |
| Ephemeral/in-memory | `OSCOPE_CHDB_SPEC=chdb::memory:` | Available now. Process-local state is not expected to survive close or restart. |
| Local path persistence | `OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/oscope-data`; the standalone default is `chdb:./oscope-data` | Available now. Reopening the same local directory retains the database, as covered by the restart integration test. It is not object-backed Durable recovery. |
| Local Durable development mode | `OSCOPE_DURABLE_ROOT=/absolute/path jolt -M:durable-server-dev` | Available with a qualified chDB Durable V1 native library. Uses the POSIX object backend, fenced single-writer lease, acknowledged WAL flushes, checkpoints, and recovery through the published head. |
| Object-backed Durable mode | `OSCOPE_DURABLE_BACKEND=s3` plus the S3 settings below | Available with the same qualified native library through the Jolt-native libcurl/SigV4 backend. Recovery uses the last CAS-published Durable checkpoint and WAL chain. |
| In-process Durable SDK | `oscope.embedded/start!` with a Durable writer dbspec | Available with the same qualified native library. Instrumented application code exports spans, logs, and metrics directly into the shared writer and queries them through oscope without OTLP or HTTP framing. |

These modes reserve “Durable” for the official
[chDB Durable overview](https://github.com/chdb-io/chdb/blob/db10b548a3e1e21e51c213baf863cb1050963d9c/docs/durable/index.mdx)
and
[Durable V1 protocol](https://github.com/chdb-io/chdb/blob/db10b548a3e1e21e51c213baf863cb1050963d9c/docs/durable/protocol-v1.mdx):

- `execute()` runs one mutation locally and appends it to the in-memory WAL
  buffer; its success is not yet a durability acknowledgement.
- `flush()` uploads a WAL segment and commits its reference to `head.json`;
  an application promising successful-request recovery on another machine
  must wait for this operation to complete before returning success.
- `checkpoint()` publishes a full database backup as the new recovery base and
  clears its covered WAL references only after the manifest update succeeds.
- `open()` restores the published base and replays its WAL segments in order.
- `close()` stops new operations, drains queued work, flushes, releases the
  writer lease, and cleans up local resources.

The ordinary `-M:server` path does not establish that remote durability
promise. The `-M:durable-server-dev` path does: it does not return a successful
OTLP response until the request's statement WAL is flushed and published.
Arrow and Parquet remain bounded data exports, not database checkpoints, WAL
segments, or a database recovery mechanism.

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

The raw event explorer uses the same static-first pattern. Log queries can
select service, case-insensitive body text, severity, time window, and a limit
up to 100. Metric queries select service, case-insensitive metric name, gauge,
sum, or histogram kind, window, and limit. These fields are parameters or
closed choices; they cannot supply SQL, table names, or expressions.

### Browser regression tours

The [Playwright storyboard](docs/demo/README.md) drives a fixed checkout OTLP
fixture through the real receiver, trace/event viewers, metric distribution,
and Plotje editor. Its assertions generate four screenshots and an optional
GIF, so the documentation path exercises the same behavior as the browser
regression test.

### Run with Durable local storage

During cross-repository development, the standalone collector can use the real
Durable V1 POSIX backend instead of opening `oscope-data` directly:

```sh
env JOLT_CHDB_LIB=/path/to/chdb-26.7.2-or-newer/libchdb.so \
    OSCOPE_DURABLE_ROOT=/absolute/private/path/oscope-durable \
    jolt -M:durable-server-dev
```

The Durable root contains immutable checkpoint/WAL objects and the CAS-protected
head. Native chDB recovery uses a private scratch directory and never opens that
root as a database directory. The default owner is `oscope`; every process start
gets a UUIDv4 instance identity. Startup checkpoints schema migrations before
the listener is bound. Each successful OTLP export flushes its statement WAL
before the server returns 2xx. Durable admission spans export through flush, so
a concurrent request receives 429 and cannot cross an earlier request's
persistence boundary; a failed durability boundary returns 503. Both failures
close the HTTP connection because the rejected streaming body may be unread. A
clean shutdown flushes WAL and releases the lease. After an unclean stop, a new
instance waits for the 30-second lease to expire. `OSCOPE_DURABLE_FORCE=true`
is an explicit operator takeover and should only be used after proving the old
process is gone.

For S3-compatible storage, select the Jolt-native libcurl SigV4 backend. The
bucket must already exist; oscope creates only keys below the configured prefix
and stable object identity:

```sh
env JOLT_CHDB_LIB=/path/to/chdb-26.7.2-or-newer/libchdb.so \
    OSCOPE_DURABLE_BACKEND=s3 \
    OSCOPE_DURABLE_OBJECT_ID=telemetry-prod \
    OSCOPE_DURABLE_S3_ENDPOINT=https://s3.example.com \
    OSCOPE_DURABLE_S3_BUCKET=observability \
    OSCOPE_DURABLE_S3_PREFIX=team-blue \
    OSCOPE_DURABLE_S3_REGION=us-west-2 \
    OSCOPE_DURABLE_S3_ACCESS_KEY="$AWS_ACCESS_KEY_ID" \
    OSCOPE_DURABLE_S3_SECRET_KEY="$AWS_SECRET_ACCESS_KEY" \
    OSCOPE_DURABLE_S3_SESSION_TOKEN="$AWS_SESSION_TOKEN" \
    jolt -M:durable-server-dev
```

`OSCOPE_DURABLE_OBJECT_ID` defaults to `oscope` and is the stable logical
database identity; it is deliberately separate from the per-process UUIDv4
lease instance. Optional transport controls are
`OSCOPE_DURABLE_S3_MAX_ATTEMPTS` (default 3, maximum 8),
`OSCOPE_DURABLE_S3_CONNECT_TIMEOUT_MS` (default 10000), and
`OSCOPE_DURABLE_S3_TIMEOUT_MS` (default 300000). Credentials are passed only to
the backend and are not copied into bounded operator diagnostics.

Startup and terminal failures print a bounded operator category before exiting
nonzero. Recognized categories include `lease-held`, `lease-fenced`,
`corrupt-head`, `engine-incompatible`, and bounded object-store authentication,
permission, throttling, transport, provider, response, and configuration
failures; each includes a fixed recovery action. Exception messages,
credentials, backend paths, owner names, and instance IDs are not copied into
this diagnostic.

The qualified process-crash gate builds the standalone Durable server, crosses
two HTTP 200/flush boundaries separated by `SIGKILL` and normal lease expiry,
then verifies both traces through a separate read-only executable:

```sh
env JOLT_CHDB_LIB=/path/to/qualified/libchdb.so \
    test/durable_crash_reopen.sh
```

The hosted S3 variant starts the pinned MinIO image, uses the ordinary exact
dependency pins, kills two distinct server processes after their HTTP 200/flush
boundaries, and verifies exactly one copy of each trace through a fresh S3
reader:

```sh
env JOLT_CHDB_LIB=/path/to/qualified/libchdb.so \
    OSCOPE_DURABLE_CRASH_BUILD_ALIAS=test-durable-s3 \
    test/durable_s3_crash_reopen.sh
```

The manual `durable-aws-recovery` workflow performs that same crash/reopen
acceptance against a pre-provisioned AWS bucket using GitHub OIDC temporary
credentials. Configure the `aws-durable-ci` GitHub environment with the
non-secret variables `AWS_DURABLE_ROLE_ARN`, `AWS_DURABLE_BUCKET`, and
`AWS_DURABLE_REGION`. Each workflow attempt uses the isolated prefix
`ci/oscope/<run-id>-<run-attempt>`, passes the AWS session token to the SigV4
transport, and neither creates nor deletes the bucket. The role needs only
`s3:GetObject` and `s3:PutObject` on
`arn:aws:s3:::BUCKET/ci/oscope/*`; expire the shared `ci/` subtree with a bucket
lifecycle rule.

Optional settings are `OSCOPE_DURABLE_OWNER`, `OSCOPE_DURABLE_INSTANCE`,
`OSCOPE_DURABLE_DATABASE`, `OSCOPE_DURABLE_SCRATCH_PARENT`,
`OSCOPE_DURABLE_LEASE_TTL_MS`, `OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS`, and
`OSCOPE_DURABLE_CLOCK_SKEW_MS`. Invalid settings fail before the backend root is
created. The server remains loopback-only.

This development alias intentionally uses sibling-local library roots. The
ordinary dependency is pinned to the reviewed control-plane commit. Both paths
require a chDB release exporting the Durable V1 ABI; the current stable 26.7.0
bundled pin is insufficient.

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

## Embed the SDK, Durable writer, and viewer

`oscope.embedded` packages the direct in-process path behind one lifecycle:

```clojure
(require '[oscope.embedded :as embedded]
         '[otel.sdk :as sdk]
         '[otel.trace :as trace])

(def runtime
  (embedded/start!
   {:db-spec {:vendor "chdb-durable"
              :backend durable-object-backend
              :owner "checkout"
              :instance "checkout-1"
              :database "default"}
    :sdk-options {:service-name "checkout"
                  :processor :batch
                  :metrics? true
                  :logs? true}}))

(trace/with-span [_ (sdk/tracer "checkout.http") "POST /checkout"]
  (handle-checkout))

;; Stop application ingress first, then drain and retire the owned runtime.
(embedded/stop! runtime)
```

The returned `:source` is the ordinary live oscope query source and can be
given to the web or native UI handlers. The exporter owns schema migration,
checkpoints it before startup returns, and confirms a Durable flush after every
non-empty SDK batch. Shutdown drains the SDK, retires queries, checkpoints the
committed WAL by default, and finally closes the writer. A failed boundary is
reported as `{:status :closing ...}` and may be retried with `stop!`.

This path avoids OTLP encoding, HTTP framing, and receiver decoding. The current
chDB insert path still materializes `JSONEachRow` SQL internally; protobuf or
gRPC would not remove that in-process storage encoding. Export acknowledgement
is at-least-once: an ambiguous failed attempt followed by an SDK retry can
produce a duplicate.

Embedded render loops and other UI fibers must not call the source's JDBC-backed
load commands directly. Use `oscope.embedded.query` to move that blocking work
onto one owned OS thread and publish a small immutable cache:

```clojure
(require '[oscope.embedded.query :as embedded-query])

(def sampler
  (embedded-query/start!
   {:load! #(get-in ((:load-command (:source runtime))
                     :recent-spans
                     {:signal :spans :field :span-name
                      :window :15m :limit 20})
                    [:table :rows])
    :interval-ms 1000
    :timeout-ms 2000
    :max-rows 20}))

;; Rendering only dereferences the cached value; it never queries chDB.
(def display-model (embedded-query/snapshot sampler))

;; Retry a bounded stop until the native query has really completed and joined.
(loop []
  (when-not (= :closed (:status (embedded-query/stop! sampler)))
    (Thread/sleep 50)
    (recur)))
;; Only now may the shared source be retired.
(embedded/stop! runtime)
```

The load function must use a backend-bounded selection; `:max-rows` separately
bounds retained display data. A timeout publishes `:error` before the first
good sample or `:stale` while retaining the last good rows. It does not interrupt
native JDBC work. The cadence fiber notices stop without waiting for the query
timeout, but the owned query thread is joined rather than interrupted. If a
native call is still running, `stop!` returns `:stopping`/`:joining-query` after
the configured stop bound; retry it and keep the source open until it returns
`:closed`. Total shutdown latency can therefore include the native operation's
actual completion time. Failure snapshots retain only bounded class/message
strings and never retain a Throwable or an invalid query result.

An out-of-process viewer opens a read-only Durable connection after a published
flush or checkpoint. It restores the immutable head selected at open time into
private scratch storage; it does not need the writer's local state and does not
tail uncommitted changes.

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
  correlated-log, filter-option, span-tree, raw-log, and metric-point contracts.
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
- `oscope.ui.events` is the third sibling surface: bounded raw log and metric
  rows with progressive two-second refresh and direct correlation links back
  to trace detail.

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

The optional JVM-only Typed Clojure pilot checks a deliberately small subset
of the pure command/query/view-model contracts and nine named mutation controls,
including cumulative-histogram temporality provenance:

```sh
clojure -M:typed-check
```

The checker and its Clojure/JVM dependencies are dev-only and are absent from
all ordinary Jolt aliases. See the [exact checked boundary, limitations, and
mutation evidence](docs/TYPED_CLOJURE.md).

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

The Durable implementation has a sibling-local integration lane. It uses the
real oscope loopback server/exporter/viewer composition and real native chDB,
checkpoints schema before ingress, sends one OTLP trace, log, gauge, sum, and
histogram over HTTP/1.1, flushes before each 200 response, and verifies exact
per-kind counts, UI querying, and Arrow/Parquet export after provider
reconstruction and read-only recovery. It also proves the nondeterministic
schema-migration record is covered by the checkpoint and absent from every
statement WAL:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -M:test-durable-dev
```

This alias intentionally overrides `jolt-chdb` and `jolt-otel-clickhouse` with
`../jolt-chdb` and `../jolt-otel-clickhouse` so it can test coordinated local
changes. The ordinary dependency declaration uses the reviewed exact commit
pin listed below.

When `JOLT_CHDB_LIB` names the qualified 26.7.2-rc.2 library, this lane runs the
real integration, including backup/restore and classification. With the stable
26.7.0 pin, the runner reports that integration as skipped and runs the
configuration, startup ordering, request acknowledgement, failure, and
shutdown controls; it never fabricates a successful checkpoint over the absent
ABI. The test uses the POSIX Durable provider and reconstructs it from its
filesystem root before read-only reopen, so recovery does not rely on the
in-memory backend oracle.

The opt-in S3 app gate starts a pinned MinIO container, launches the real oscope
collector over the S3 namespace configuration above, crosses three HTTP
200/WAL flush boundaries for a trace, correlated log, and gauge/sum/histogram
batch, performs a clean release, and reconstructs the backend. A fresh native
connection proves exact per-table counts and valid Arrow/Parquet exports for
every recovered signal shape:

```sh
env JOLT_CHDB_LIB=/path/to/qualified/libchdb.so \
  test/durable_s3_minio.sh
```

The `durable-s3-e2e` workflow runs the same script with the publishable
`:test-durable-s3` alias. It qualifies the native library through jolt-chdb's
pinned asset and upstream ABI oracle, then exercises oscope only through exact
Git dependency pins. The script defaults to `:test-durable-s3-dev` locally so
coordinated sibling changes remain testable before publication.

The same service path can be compiled with the Durable aspect pack and checked
against its Hegel transition model. This also proves the explicit libcurl
transport entry point remains reachable in a standalone Jolt executable:

```sh
env JOLT_ASPECT_JOLT=/absolute/path/to/aspect-capable/jolt \
    JOLT_CHDB_LIB=/path/to/qualified/libchdb.so \
    test/durable_s3_aspect_smoke.sh
```

The same integration test also has an opt-in compiled aspect lane. It binds a
semantic journal around the real oscope lifecycle, weaves only the six Durable
control entry seams, and validates the completed privacy-shaped command history
offline with the Hegel transition model. The same build composes an oscope
consumer that emits one internal span and one duration-histogram observation
per Durable control operation. Its attributes are limited to the closed
operation, outcome, and failure-category sets; it never records arguments,
object keys, owner identities, result bodies, or exception messages. Generic
OpenTelemetry suppression is checked before observation, so an exporter backed
by the same Durable chDB connection cannot recursively instrument itself:

```sh
env JOLT_ASPECT_JOLT=/absolute/path/to/aspect-capable/jolt \
    JOLT_CHDB_LIB=/absolute/path/to/libchdb.so \
    test/durable_aspect_smoke.sh
```

This subproject uses sibling-local roots for `jolt-chdb`,
`jolt-otel-clickhouse`, and `jolt-aspect-packs`; it is an integration/release
gate rather than a publishable dependency. Its compiler report at
`target/aspects.edn` must be checked with the pack's report validator, while the
ordinary `-M:test-durable-dev` lane remains the non-woven comparison. The build
explicitly embeds jolt-chdb's canonical runtime resources so the standalone
binary reads the same ABI descriptor used during compilation. The woven lane
uses a release-mode build and the same real loopback transport as the unwoven
integration.

oscope now requires Jolt v0.8.3 or newer, matching the pinned Durable control
plane. Older app builders may incorrectly inherit `jolt.ffi` from the compiler
image and produce a binary with an unbound `jolt.ffi/errno`. The standalone
smoke builds and starts the real receiver long enough to reject that artifact
class:

```sh
env JOLT_CHDB_LIB=/path/to/libchdb.so \
    JOLT_BIN=/path/to/jolt-v0.8.3-or-newer \
    JOLT_TOOLCHAIN=/path/to/jolt-with-chez-10.4.1 \
    test/standalone_build_smoke.sh
```

## Exact dependency baselines

- `chucklehead-dev/jolt-otel-clickhouse` `cd78aa5766775f7e9caea2722d0b33b039745946`
- `chucklehead-dev/jolt-chdb` `58f090caa31445bcf9403a15bdd01b1901a4e860`
- `casselc/jolt-http` `35d1d7f9ebdc796ee9bd4c80745298b2c8b7fdf8`
- `casselc/glitter` `f4e3eb83015566e4cadaedd7f5e8ad80dc57404f`
- `casselc/glimmer` `6dab5597dc0d912793fe175d0d3cbb9e75f11426`
- `jolt-lang/glimmer-gtk` `ce79d45698d36ccf496397bb85974e3cce6abfd8`
- `casselc/data.json` `932444043c0c06f9e295ba4963419b2481e9dd07`

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
