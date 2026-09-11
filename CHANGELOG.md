# Changelog

## Unreleased

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
  boundary; ephemeral memory and not-yet-owned Durable file launches fail
  closed.
- Add an explicit operator-authorized typed span schema startup boundary for
  embedded and standalone collection. Base schema and approved additive DDL
  complete before exporter or ingress startup, and only installer-confirmed
  descriptor capabilities reach export and the live query context.
- Refresh the OpenTelemetry exporter and viewer to the reviewed typed-attribute
  and canonical crypto stack while retaining the existing opt-in schema
  boundary.
- Bound the standalone HTTP dispatcher to its configured workers and waiting
  capacity despite Jolt 0.8.3's advisory ThreadPoolExecutor queue model; excess
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
