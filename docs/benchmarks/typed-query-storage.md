# Typed query and storage benchmark

This opt-in local benchmark compares Oscope's approved typed span columns with
the existing `SpanAttributes Map(String,String)` fallback. It exercises the
real standalone loopback OTLP/HTTP JSON receiver, exporter, persistent chDB
database, restart, live query source, and schema acquisition path. It does not
measure a mock or call exporter query helpers directly.

Typed timings include Oscope's schema-bound live-source planning and result
validation. The comparison baseline uses explicit bounded JDBC queries over
the generic string map because Oscope deliberately does not pretend that
lexical strings are a typed numeric capability. The standalone collector does
not install an OTel SDK or instrument its viewer/storage calls, preserving its
existing no-feedback-loop boundary.

Each fresh database receives the same deterministic spans. Four out of every
five spans carry `benchmark.int64` and `benchmark.boolean`; the remainder carry
neither. Int64 cardinality is controlled independently. The harness checks the
known present/absent distribution and compares exact selected trace/span IDs
for an Int64 equality filter and a Boolean `true` filter. It also compares
`count`, `min`, `max`, and `avg` over all present Int64 values. A mismatch fails
the run before an artifact is accepted.

Setup, OTLP ingestion, restart, and each query are timed separately. Every
query records its first execution after reopen, then discards configured
warmups and reports warmed samples. A p50 needs at least 5 samples, p95 needs
20, and p99 needs 100; unsupported percentiles are omitted and explicitly
marked unsupported. The first-after-reopen value is not a cold-cache claim,
because this harness does not evict operating-system or chDB caches.

One invocation with no repetition argument remains a useful single-process
characterization, but its report says `counterbalanced? false`. For comparative
qualification, pass an even process-repetition count from 2 through 10. The
runner launches a fresh Jolt process for each complete matrix, runs string
fallback first on even-indexed repetitions and typed first on odd-indexed
repetitions, then accepts a combined report only when every declared repetition
is present in index order with identical source/runtime provenance. It checks
the clean worktree and exact revision before every shard, before combination,
and again before publishing the canonical artifact. All benchmark and combine
processes use reproducible dependency resolution. This
balances first/second mode order; it does not remove thermal, power, filesystem,
or other host effects.

## Run it

Install the repository's pinned native chDB library and run Jolt through the
workspace's required Chez 10.4.1 wrapper. A small correctness smoke is:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  scripts/run-typed-query-storage-benchmark.sh smoke
```

The later representative qualification command is identical except for the
profile:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  scripts/run-typed-query-storage-benchmark.sh representative
```

That command is deliberately still a single unbalanced characterization. A
minimal counterbalanced representative pair uses two fresh processes:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  scripts/run-typed-query-storage-benchmark.sh representative 2
```

The representative profile runs both storage modes for each combination of
1,000, 10,000, and 50,000 rows with Int64 cardinalities 2, 32, and 256. Each
operation has 10 warmups and 100 measured samples. This is intentionally not a
normal test or CI gate.

The validated, read-back-checked EDN report is written to
`target/profiles/typed-query-storage.edn` and is capped at 1 MiB. It records the
full Oscope source SHA captured by the runner after it rejects a dirty
worktree, exact direct dependency SHAs, Jolt/Chez runtime identity, loaded and
declared chDB versions, process-repetition indexes, and actual mode order. The
artifact contains only bounded scalar summaries, coverage counts, aggregates,
and storage totals; it does not retain telemetry rows or query result IDs.
Per-process shards live only in a unique temporary directory beside the final
artifact and are combined after validation. The runner keeps the prior
canonical artifact in place and atomically replaces it with the new combined
report only after read-back validation. A failed or interrupted replacement
therefore leaves the last validated artifact available. Missing, duplicate,
reordered, biased, or stale-provenance shards fail closed. One repetition never
claims counterbalance; more than one must be an even, complete alternating set.

## Storage footprint scope

After ingestion, the harness executes `OPTIMIZE TABLE otel_traces FINAL` and
reads active `otel_traces` part totals from `system.parts`. The comparison uses
`bytes_on_disk` and also reports compressed and uncompressed data bytes, part
count, and row count. This measures the physical active parts of that one table
under the loaded chDB build. It is not total database-directory usage, memory,
WAL, object-storage, or a prediction of long-running production compaction.
The benchmark fails instead of inventing a ratio if chDB cannot expose a
positive, row-reconciled `bytes_on_disk` value.

Smoke timings only prove that the harness and correctness oracles work. Do not
publish them as production performance qualification. Record host CPU, memory,
filesystem, power state, and competing load alongside any representative run;
repeat whole-process runs before drawing conclusions about small differences.
Use the even repetition form above so repeated runs counterbalance mode order;
repeating the one-process form preserves its fallback-first ordering.
