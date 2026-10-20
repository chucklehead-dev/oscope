# Rich real-socket ingestion screen

Source checkpoint46b397934fa414427e317abc6bdadbb535be5d0b. Published exporter
34cc810 (subsequently merged unchanged at1e7cc8d); independent codec98e93e2.
Official stock Jolt0.8.23, native26.9.0, exact checksums in the runner.

This is **Oscope's real HTTP receiver**, not the earlier direct-SDK 5k-row
callback workload. Those earlier ~14.8k local observations are not contradicted
or replaced by these numbers. This screen includes socket/client framing,
bounded body consumption, JSON parsing, full OTLP validation/normalization,
export preparation and confirmed Durable response. Client payload JSON is
pre-encoded outside timing; producer generation is excluded.

## Initial diagnostic pair

One sequential process per mode: configured compact JSON, then explicit codec
with all three optional caches. Each has one warmup and eight measured requests
of512 rich spans:14 attributes, event, real SDK link context, trace state and
Unicode. Exact body653,705bytes. The otherwise identical 5k-row fixture is
6,386,637bytes and does not fit the unchanged1MiB receiver bound.

| Mode | Pooled confirmed rows/s | Request p50 | p90 | p95 | p99 | Estimated allocated B/row |
|---|---:|---:|---:|---:|---:|---:|
| Configured JSON |662|708.02ms|1044.30ms|1044.30ms|1044.30ms|671,514|
| Explicit codec |1,029|483.79ms|540.84ms|540.84ms|540.84ms|329,463|

These are one process per mode, fixed order, **not ABBA or a population tail**.
With eight observations, p90/p95/p99 are the sample maximum. Timestamps were
generated once per process, not a bit-identical shared input seed. Do not turn
the pooled difference into a deployment-wide or isolated causal claim.

For the codec arm, coarse inclusive wall phases / total measured window:

- Body consumption + UTF-8 String construction + JSON parsing: **61.5%**.
- Span projection + encoding: **2.8%**.
- Explicit WAL preparation: **0.3%**.
- Owned Durable API: **8.3%**, **including** native stream insertion (**0.4%**).

Do not sum overlapping phases. The remaining time includes OTLP validation/
normalization, other request/transport work and instrumentation. The body phase
does **not** isolate the JSON reader from body-copy/transport costs. Next useful
attribution: separately measure the actual document read and OTLP decoder.
Even eliminating that entire61.5% phase would give only about2.6x under the
fixed-other-cost assumption, not the25k target. Conversely, eliminating the
encoder alone barely moves this particular HTTP workload. This is not a bound
on direct SDK or larger-batch library throughput.

Estimated allocation uses coarse live-heap plus cumulative GC-byte deltas;
raw counters are retained. It covers the whole process/window, **not encoder
allocation alone**, and is not a precise per-allocation profiler. Process CPU
(all threads) was6.22s/6.19s wall configured and3.95s/3.98s codec. GC wall was
218ms and127ms. These totals do not isolate phase-specific CPU or rule out
individual I/O waits. Reported values retain all samples, p50/p90/p95/p99,
inclusive phase counts, CPU ticks, GC counters and RSS snapshots.

Both modes independently recovered **4,608 rows each** (warmup included),
checking every value of22core ClickStack columns and exact multiplicity from
fixture semantics. This excludes the additional EventsJSON/LinksJSON text
columns; the actual SDK/library and maintained Oscope regression cover those
separately. Writer and reader processes terminated0. Copies/cmp receipts,
independent semantic validation and source hashes passed before each exact
owned synthetic store was retired. No native databases or binaries retained.

The new fixture first exposed the record-context gap tracked in exporter#149.
Initial setup probes also missed native loading/scratch-parent creation; those
failed before qualification. The guarded record fix, not a map-normalizing
fixture workaround, made real socket ingestion and recovery pass.

## Reproduce

From this worktree, run `bash research/socket-ingest-20261020/run-pair.sh NEW_DIR`.
The committed runner selects fixed stock/native checksums, a bounded workload,
separate readers, raw scalar reports and success-only cleanup. Do not edit it
or its hashed sources during a run. Failures retain their store. No concurrent
local compiler, native benchmark or external-agent review ran in the timed
windows. Original small logs/load observations remain under workspace evidence.

The initial benchmark slice has independent review as recorded below. It is
not S3/Langfuse, all signal/value shapes, maximum sustainable concurrency,
complete app packaging, Rust-relative or target qualification. The original
~25k p50/~20k p99 library objective remains active and unmet.

## Review and next bounded diagnostic

Claude read-only review approved the source/timing/recovery/interpretation
slice at34826a1 with no blockers. Its one count sentence incorrectly describes
both arms as9codec/stream calls; the actual reports/source require **0/0** for
configured and **9/9** for codec. Codex verified these and owns executed gates.
The reviewer recommends matched-order replication or separating document read
from body-copy/transport before a stronger attribution claim. No broader
qualification follows from that advisory review.

Selected data.json0f51b99's `next-string-special` separately searches the whole
remaining document for quote and backslash. A valid array of unescaped empty
strings makes every absent-backslash search scan the suffix. The actual helper
call/output control in `suffix-search-control.clj` yields16/32/64/128calls for
49/97/193/385input characters; source-modeled escape-search visits are
392/1552/6176/24640. This is a quadratic work pattern, not a measured native
instruction count. Its contribution to the Unicode-escaped socket workload is
still unproven. Tracked at jolt-aspect-packs#184 (the data.json fork disables
issues). Check current source before changing pins and preserve existing fork
Unicode/cursor/error fixes. Compare Reader-backed `json/read` and bounded/fused
scanning before considering a new parser implementation.

The [follow-up reader screen](reader-layer-results.md) reuses the existing
fork's native token reader on stock Jolt. It preserves the earlier work and
separates the strong string-array win from the smaller actual-wire parsing
improvement. Application pins and confirmed-throughput claims are unchanged.

The [confirmed socket follow-up](reader-pair-results.md) uses matched JSON
revisions, handler-scoped reader bindings and independent recovery. Its small
pooled gain, worse maximum sample and higher estimated allocation do **not**
justify enabling the native token reader by default.

Two further matched confirmed pairs are recorded separately:

- [Fused scalar finder](fused-pair-results.md): parser time improved, but no
  overall throughput win was established.
- [Recovered whole-document reader](whole-reader-results.md): the first local
  pair showed about 2x confirmed throughput with lower estimated allocation.
  This is promising source-mode research, not a default/pin change or a
  sustained-tail, general conformance or original throughput-target result.
