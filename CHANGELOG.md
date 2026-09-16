# Changelog

## Unreleased

- Run each typed socket fixture in a fresh bounded child process, preserving
  chDB's immutable physical-path lifetime and all restart/readback assertions.
  Require an absolute child executable and real execution/count/exit receipts;
  retain evidence when child settlement is uncertain. Refs #109.

- Add an always-registered local SDK-to-OTLP-socket/native-storage regression
  for synchronous finite counter admission, signed up/down values and false/zero
  metric attributes (Refs casselc/otel#3). Qualification requires the reviewed
  dependency stack; metric typed-column promotion is not claimed.

- Confirm timed-out test children have exited before deleting native fixture
  scratch; preserve unconfirmed fixtures and fail with closed cleanup status
  rather than treating a termination request as child retirement (Refs #108).

- Retain closed, payload-free per-request flush/checkpoint observations before
  HTTP acknowledgement, without changing persistence, retry, or failure behavior
  (Refs #4). An optional diagnostic sink receives only the bounded observation.

- Add desktop/mobile full-page workbench captures to the existing browser demo
  guide, documenting the synthetic local fixture and bounded usability checks.

- Register readiness regression tests in both canonical test runners so
  ordinary and hosted headless CI exercise the listener discovery contract;
  real native readiness gates remain explicit opt-in qualification.

- Reject explicit false typed-schema startup envelopes with the canonical safe
  validation error before schema effects or embedded owner acquisition; retain
  nil as absent and add cause-free, secret-safe boundary regressions. Refs #107.

- Give the full-page telemetry workbench its explicit dark theme and a
  responsive attribute layout, preserving full labels and values without
  changing shared viewer fragments. Add source and browser regressions. Refs #106.

- Add opt-in embedded-viewer and standalone-listener readiness callbacks and
  private atomic readiness files, with actual ephemeral ports, launch identities,
  nonblocking lifetime ownership, and retryable terminal publication/rollback.
  Readiness reports listener discovery, not Durable freshness. Refs #35.

- Requalify the minimal embedded profile at OTel `4d61f8e9` and jolt-chDB
  `95d7b2b3`, retaining the already-current ClickHouse exporter `14a2998a`.
  The real fixture selects the converged interruptible HTTP provider under the
  application's canonical key, stalls independent application and remote OTLP
  requests, and proves prompt application cancellation, local typed Durable
  readback, bounded redacted status, failed non-replayed remote delivery, and
  ordered idempotent shutdown. Its parent removes native scratch only after
  the anchored chDB child exits. Refs #77.

- Replace the minimal embedded profile's graph-only stub fixture with a fresh
  hosted-Linux native process using the converged `jolt-lang/db` provider. It
  keeps authoritative SQLite application state beside one real local-POSIX
  Durable chDB telemetry writer, installs confirmed typed span descriptors,
  starts one OTel SDK owner, flushes and reads back a bounded span, retires the
  query, and closes SDK/source/checkpoint/Durable/SQLite ownership exactly once
  without adding viewer or listener dependencies.
  Samizdat HTTP migration and remote Langfuse availability remain outside this
  qualification. Refs #77.

- Replace embedded-query result polling with Jolt's event-driven channel
  selection across result, stop, and timeout events. The single owned query
  thread, cadence timing, stop-first race behavior, bounded retryable stop, and
  no-native-interrupt contract remain unchanged. Refs #38.

- Requalify the minimal embedded profile against jolt-chDB's merged database
  provider convergence (`3552a257`). A modeled Samizdat migration fixture
  repoints the canonical `jolt-lang/db` key to merged `casselc/db` main
  (`96324713`) and verifies from resolved git metadata that it descends from
  reviewed provider `6db79163`. The graph resolves authoritative SQLite plus
  Durable chDB with exactly one physical `db/**` source root. A pinned
  pre-convergence fixture remains as the causal two-root red control.
  Samizdat's actual DB and HTTP migrations, remote-stall/Langfuse
  qualification, and Durable freshness/status exposure remain separate
  follow-ups. Refs #77.

- Close embedded-query executor shutdown and termination-wait failures into the
  shared redacted lifecycle descriptor instead of throwing the original
  exception to the caller. Stop remains retryable, never interrupts blocked
  native work, and reports `:closed` only after real executor termination.
  Refs #99.

- Replace embedded-query snapshot exception class/message prefixes with a
  closed failure type, phase, and category for load, executor-submission, and
  timeout outcomes. Throwable details, SQL and values, paths, endpoints, and
  credentials are never retained; bounded string length is explicitly not
  treated as redaction. Last-good rows, stale/error publication, timeout
  diagnosis, and later-sample recovery remain intact. Refs #97.

- Add schema-bound typed-log discovery, filters, projected values, and six-way
  historical coverage to the embedded and standalone viewer. String, Boolean,
  and Int64 fields are acquired only from the installer-confirmed capability;
  saved URLs retain the complete schema binding and fail closed after catalog
  changes, while generic log exploration remains available. A separate
  log-target browser fixture qualifies real OTLP ingestion, projected values,
  coverage, and canonical URL reload without conflating trace capabilities.
  Refs #93.

- Add a same-repository `profiles/embedded` dependency root which exposes the
  canonical embedded lifecycle and bounded query helper without jolt-http or
  jolt-otel-viewer dependencies. A runnable fixture proves one SDK owner,
  listener/UI namespace isolation, query retirement, and exact-once resource
  close. The pinned Samizdat 22be90d graph is retained as a causal rejected
  control while its divergent DB providers remain unconverged; no dependency
  winner is presented as SQLite plus Durable qualification. Refs #77.

- Route installer-confirmed `otel_logs` / log-record attribute capabilities to
  the exporter's typed-log projection in both standalone and embedded startup.
  The generic log attribute map remains intact.

- Complete the managed-config store qualification with an independent-process
  restart through the normal XDG loader, plus causal controls that demonstrate
  why direct live-file writes and pre-delete/non-atomic replacement fallbacks
  violate the prior-file preservation contract. Refs #91.

- Add the closed, revisioned foundation for a private managed configuration
  store. Only the canonical platform user path is eligible for writes;
  explicitly selected command-line and environment files remain read-only.
  Validation and deterministic encoding precede effects, stale revisions fail
  under a cross-process lock, and public results retain only fixed categories
  and opaque content revisions. The Linux x86-64 adapter uses descriptor-
  relative no-follow access, owner/mode proofs, file and directory sync, and
  same-directory native rename without pre-deleting the prior file. Other
  platforms remain fail-closed pending native qualification. Refs #91, #31,
  #33.
- Discover an existing platform user configuration when neither `--config` nor
  `OSCOPE_CONFIG` selects a file. XDG, macOS, and Windows defaults remain
  optional and read-only; relative roots never become working-directory
  lookups, explicit selectors keep precedence, and `--check-config` diagnostics
  retain their path and secret redaction. Refs #33.

- Render typed field types and coverage in user-facing language. Filter results
  now identify Boolean, Int64, or String values explicitly, while the conserved
  coverage table explains valid, empty, absent, invalid, fallback, and
  unavailable historical rows without exposing internal status keywords.

- Qualify the embedded typed-attribute path through the real SDK, Durable
  writer, exporter projection, and live query stack. The approved manifest is
  consumed only at startup; ingestion and query state share the exact opaque
  installer-confirmed descriptor capability and retain no manifest value.

- Route versioned `:durable-local` and `:durable-s3` files through the existing
  Durable standalone owner. The launcher shares config precedence and redacted
  check-only handling, retains the legacy environment surface, resolves S3
  credential references only at runtime, and gives typed schema state a
  distinct fixed registry object. Refs #4, #33.

- Add a closed, versioned `oscope.embedded/status` snapshot for application
  health endpoints. It reports lifecycle phase and bounded local/remote span
  pipeline counters without exposing ownership-bearing objects, endpoints,
  credentials, exceptions, or telemetry values. Durable freshness and the
  last successful persistence boundary remain explicitly unavailable until a
  public jolt-chdb capability can support those claims. Refs #77.

- Add a viewer-only embedded lifecycle over an existing source and connection.
  It exposes the normal workbench, event, chart, and editor surfaces on an
  exact loopback authority without OTLP ingress, reports the actual ephemeral
  URL, and owns only its listener and bounded query-request workers. Retryable
  stop and startup rollback leave the caller's SDK, exporter, source,
  connection, and Durable writer under the original embedded owner.

- Report the protected Langfuse gate's failure through a closed set of stage,
  status, and bounded observation-count categories. Terminal summaries retain
  no endpoints, trace IDs, headers, credentials, bodies, exception details, or
  arbitrary values, so operators can distinguish export from semantic-readback
  failures without weakening the gate's redaction boundary. Cleanup always
  runs, while a simultaneous cleanup failure cannot replace the primary stage.

- Pin merged `casselc/otel` HTTP-provider convergence so complete
  Content-Length and chunked TLS responses finish without waiting for a later
  raw transport close. The selected transport still rejects truncated and
  close-delimited responses that omit TLS `close_notify`; a resolved-classpath
  control rejects the prior OTel and HTTP provider pair.

- Align the protected Langfuse workflow's GitHub variable and secret context
  names with the documented `OSCOPE_LANGFUSE_*` inputs, and reject the stale
  short-name mapping with a causal workflow-policy mutation.

- Consume exporter-confirmed resource, instrumentation-scope, and span
  attribute descriptors as distinct typed fields. Catalogs, queries, results,
  controls, and saved URLs retain the attribute location; legacy four-part
  bindings canonicalize only when they still identify one span field. Scope
  history reports unavailable instead of pretending a generic fallback exists,
  and local-only startup tests poison remote exporter and secret-env access.

- Add a strict non-sourcing Langfuse credential wrapper for owner-only local
  files or separately injected CI variables. It derives the Basic header over
  standard input, removes raw keys before launching the existing gate, rejects
  ambiguous or malformed inputs, and has causal non-execution/redaction tests.
  A manual protected-environment workflow qualifies real Langfuse readback
  without exposing that credentialed gate to ordinary CI.

- Align the ClickHouse exporter pin with its merged current OTel dependency.
  Hosted checks reject the prior exporter coordinate from the resolved
  classpath, compare typed direct export with real loopback OTLP ingestion, and
  retain independent local/remote pipeline failure coverage.

- Raise the source, standalone, browser, and hosted runtime floor to Jolt
  0.8.6, with version-keyed caches and a causal regression control. The
  existing Durable library and aspect-pack pins remain fixed until their
  separate protocol/model qualification stack is complete.

- Classify exporter migration-application startup failures with a closed
  operator category while keeping SQL, exception messages and data, paths,
  credentials, and telemetry values out of public diagnostics.

- Replace thrown standalone and embedded shutdown errors with a closed public
  lifecycle descriptor. Retry phase and operation remain visible, while the
  Throwable, message, cause, ex-data, stack, headers, paths, credentials, and
  telemetry attribute values cannot escape through lifecycle results.

- Pin woven Durable CI to the merged Jolt 0.8.6 aspect compiler, with exact
  source and runtime-version provenance. The already-qualified chDB and aspect
  pack revisions remain unchanged.

- Qualify typed standalone ingestion by exporting one canonical span directly
  and encoding that same span through the real OTLP loopback socket. The
  persisted ClickStack semantic and promoted columns must match for Boolean
  false, empty string, zero, exact large Int64, and signed Int64 boundaries;
  the existing absent, invalid, historical, and read-only restart cases remain.

- Pin `jolt-otel-clickhouse` to its validate-once typed descriptor capability,
  preserving connection identity checks while removing repeated immutable
  registry and catalog validation from each typed query access.

- Counterbalance typed query/storage comparisons across optional fresh-process
  repetitions. Reports now retain each repetition and actual mode order, keep a
  one-process run explicitly unbalanced, and reject missing, duplicate, biased,
  reordered, or stale-provenance repetition sets. Each process boundary and
  final publication rechecks the clean exact source under reproducible
  dependency resolution, and a failed replacement run preserves the previous
  validated artifact.

- Qualify the Langfuse OTLP/HTTP JSON profile through a real loopback socket,
  preserving canonical span identity across independently bounded local and
  remote queues. Add an opt-in live gate that reads the same nested trace from
  a standalone Oscope receiver and Langfuse's v2 Observations API instead of
  treating HTTP acceptance as semantic interoperability; credentials and
  response bodies remain outside diagnostics.

- Add an opt-in, reproducible local benchmark for approved Boolean and Int64
  span queries versus string fallback. It drives real standalone OTLP/chDB,
  checks exact cross-mode filters and Int64 aggregates, preserves restart
  bindings and coverage, reports only sample-supported percentiles, and scopes
  storage comparison to optimized active `otel_traces` parts.

- Retain scalar, per-destination delivery counts for embedded span pipelines.
  Background remote export failures remain visible through later flush and
  shutdown results without blocking healthy local delivery or cleanup.

- Qualify approved Boolean span filtering through the standalone browser path.
  False values remain distinct through OTLP ingestion, schema-bound saved URLs,
  exporter-owned filtering, and typed display; the view now shows the conserved
  coverage total beside all six availability and historical states.

- Add a table-first typed Int64 span summary for approved fields. Saved URLs
  retain the exact field ID, logical key, type, and manifest version; the
  exporter owns the half-open range, optional service grouping, and
  `count`/`min`/`max`/`avg` queries. The view separately reports all six typed
  availability states and their conserved total, including historical values
  that cannot safely participate in numeric aggregation. Raw field-selection
  forms redirect to complete four-field binding URLs before query execution;
  partial bindings and manifest versions outside positive signed Int64 fail
  closed.
- Qualify version 2 typed-attribute file configuration through the real
  standalone loopback server and persistent local-path restart. Digest-pinned
  install preserves Boolean, string, and full signed Int64 values plus every
  typed coverage status; acquire-only startup reuses exact confirmed bindings,
  rejects stale saved bindings, and performs no manifest read or schema DDL.
- Add optional embedded dual span export with independently bounded local chDB
  and remote OTLP/HTTP JSON pipelines under one SDK owner. Terminal telemetry
  failures are reported without waiving query or Durable cleanup, logs and
  metrics remain local, credentials are environment-referenced and absent from
  public lifecycle results, ambient standard OTLP settings cannot widen the
  validated remote destination, and the existing local-only API remains
  unchanged when the option is absent.
- Discover approved Int64 span attributes beside Boolean and string fields and
  filter them with exact signed 64-bit `eq`, `gte`, and `lt` predicates. The
  browser keeps values as decimal text until bounded parsing, preserving
  integers beyond JavaScript's safe-number range and exact saved bindings.
- Add a table-only typed span explorer for approved Boolean and string
  attributes. Saved URLs carry exact logical schema bindings, stale bindings
  fail visibly, and results show typed-value coverage plus the live two-query
  freshness boundary without exposing physical columns or registry identity.
  Runtime catalog acquisition remains separate from the pure query and display
  contracts, so the JVM Typed Clojure pilot continues to check those contracts
  without loading the Jolt-native exporter stack.
- Add version 2 file configuration for disabled, install, and read-only acquire
  typed-attribute modes. Version 1 normalizes to disabled; install mode uses a
  bounded read and hashes exact manifest bytes before safe EDN/exporter
  validation, enforces deployment-selector equality, redacts private
  provenance, and routes the result through a persistent database-scoped local
  registry. Acquire remains read-only through the existing typed-schema startup
  boundary; ephemeral memory launches fail closed.
- Add an explicit operator-authorized typed span schema startup boundary for
  embedded and standalone collection. Base schema and approved additive DDL
  complete before exporter or ingress startup, and only installer-confirmed
  descriptor capabilities reach export and the live query context.
- Refresh the OpenTelemetry exporter and viewer to the reviewed typed-attribute
  and canonical crypto stack while retaining the existing opt-in schema
  boundary.
- Bound the standalone HTTP dispatcher to its configured workers and waiting
  capacity despite Jolt 0.8.6's advisory ThreadPoolExecutor queue model; excess
  connections are closed before handler execution, and ordered shutdown drains
  admitted work before terminating the owned pool.
- Add the first versioned file-backed configuration slice for the standalone
  server, with closed EDN validation, CLI/environment/file/default precedence,
  field provenance, path redaction, credential references, compatibility
  environment aliases including the bounded HTTP dispatcher, and a
  side-effect-free `--check-config` command.
- Keep native Glitter and Glimmer selection callbacks responsive with one
  owned OS query worker and capacity-one latest-wins replacement. Generation
  fencing prevents stale publication, errors are bounded screen state, and
  close waits for physical worker exit before callers retire the source.
- Render singleton and extreme finite numeric chart domains without NaN or
  Infinity coordinates. One-sample lines now receive a visible point fallback,
  while one-sample areas explicitly collapse to a baseline-to-value segment.
- Pin the bounded jolt-chdb Durable retry implementation in the application and
  native qualification workflows, and expose separate writer-control and
  S3-transport deadline/backoff settings through the standalone collector
  environment.
- Follow the retry-aware Durable aspect epoch and terminal operation arities so
  hosted history validation observes each writer operation exactly once.
- Bound long-running standalone Durable recovery chains by checkpointing every
  configured number of acknowledged OTLP batches; failed checkpoints remain
  due and cannot be silently replaced by another WAL flush. Deterministic
  two-worker coverage proves cadence selection cannot double-checkpoint a
  shared boundary.
- Build standalone Durable writer configuration through jolt-chdb's validated
  dbspec constructor, so role and storage mistakes fail before collection starts.
- Qualify the Durable collector against jolt-chdb's owned worker and heartbeat
  threads, and require its woven lifecycle trace to renew before release.
- Add `oscope.embedded.query`, a bounded background query facade that keeps
  blocking chDB/JDBC work off Jolt UI fibers and joins its owned OS thread
  before a shared embedded source can be retired.
