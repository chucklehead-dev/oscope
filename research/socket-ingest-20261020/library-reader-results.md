# Exact published reader API: confirmed socket pair

This tests the extracted `jolt.codec.reader.experimental/try-read-string` API
at published component **43291a2fbcc6f1f855103c1db4264a3c13d618c1**. Both arms
select that exact Git subproject, data.json0f51b991, official stock Jolt0.8.23
and chDB26.9.0. No checkout-local reader or old data.json backend hook is used.
The source URI gates require the exact Git SHA for encoder and reader resources.

The benchmark explicitly composes public native selection with captured normal
JSON numeric/fallback functions. Numeric token conversion calls the captured
function, not the instrumented document-read Var, so those calls cannot inflate
the request parser counter. Only real handler document reads use this route;
storage metadata and the independent reader stay on the normal parser.
No per-token instrumentation or library/global default registration is added.
Benchmark-only Var observation remains distinct from production integration.

## Results

One warmup plus eight measured512-span requests per process, control first then
candidate. The generated timestamp differs per process; fixture shape/size and
source/dependency graph match, not every input byte. Both writers and separate
default-decoder readers exited zero. Each independently recovered **4,608 rows,
all22 core fields and multiplicities**. Receipts were copied/compared and all
source postchecks passed before the exact successful stores were removed.

| Measurement | Configured reader | Explicit library reader |
| --- | ---: | ---: |
| Pooled confirmed rows/s | 990.1 | 1,643.1 |
| Request p50 | 518.2 ms | 290.7 ms |
| Request p90 / p95 / p99 (eight samples: max) | 545.3 ms | 420.0 ms |
| Estimated allocation per confirmed row | 329,072 B | 264,107 B |
| Request JSON document time, total | 2,283.5 ms | 160.7 ms |
| Body + conversion + JSON inclusive total | 2,562.1 ms | 561.9 ms |
| Actual native accepts / declines (including warmup) | 0 / 0 | **9 / 0** |

This first public-API pair shows1.66x pooled throughput and about20% lower
estimated allocation. It is **not** the previous2.05x old-hook result. Do not
attribute cross-run differences solely to adapter overhead: source revisions,
other phase times, sampling and scheduling differ. No ABBA/sustained or
population-tail qualification follows. Eight samples make p90/p95/p99 maxima.

The counters inspect actual public native result vectors, not handler attempts
or startup probes. A silently declining backend would fail the acceptance gate,
even if fallback persisted all rows. Empty/falsy values remain distinguishable
from decline through the [value end] result. No new opaque backend hook needed.

Allocation is a coarse complete-window live-heap/collected-byte estimate, not an
exact object profiler or encoder-only cost. Parser/body phases overlap and may
not be summed. Nested EventsJSON/LinksJSON text is not in this readback oracle;
earlier linked-record tests cover that separate slice. Mixed/typed/concurrent
traffic, app packaging and S3 are not qualified by this span-only local pair.
Original direct-SDK Durable25k p50/20k p99 goals remain separate and unmet.

## Publication and reproduction

Exporter PR151 merged at **7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d** after all
five exact-head checks succeeded. Both core and extraction read-only Claude
reviews approved the next bounded stage. The merge tree/parents are separately
verified; source/component qualification is not automatic app adoption.

Run `bash research/socket-ingest-20261020/run-library-reader-pair.sh NEW_DIR`.
Its success-only cleanup retains small scalar reports, not large stores.
Raw reports are in `library-reader-pair/`. Next: independent benchmark review,
then explicit production Oscope handler/config integration on canonical main,
startup/unavailable-component/failure controls and real consumer verification.

Claude read-only benchmark review approved this public-API replication evidence
with no blockers. Codex recomputed the diff hash and verified the merge tree
and parents through current GitHub commit metadata; the reviewer did not execute
those checks. One reviewer sentence says counters reset after warmup: source
and receipts instead reset after startup and **before** warmup, correctly
requiring nine actual accepts for eight measured requests plus one warmup.
