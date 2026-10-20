# SDK explicit-default normalization: confirmed socket ABBA

This composes the already-selected published JSON reader/encoder with the
AnyValue explicit-default normalization optimization, not a new parser or
Durable contract. Four fresh writer processes ran baseline/candidate/candidate/
baseline, each with one warmup and eight measured requests of 512 spans.

## Results

| Process | Confirmed rows/s | Request p50 ms | Request p90/p95/p99 ms (maximum) | Estimated bytes/row |
|---|---:|---:|---:|---:|
| 1 baseline | 2,053.9 | 240.3 | 288.2 | 265,138 |
| 2 candidate | 2,100.3 | 208.5 | 324.6 | 189,410 |
| 3 candidate | 2,601.2 | 189.0 | 234.6 | 190,272 |
| 4 baseline | 1,350.8 | 349.6 | 493.6 | 264,388 |

Estimated complete-window allocation falls **28.3%**, consistently in both
candidate processes. These estimates combine live-heap and collected-byte
deltas from separate global snapshots; they are not object-allocation profiles.

Pooled figures are 1,629.8 baseline versus 2,324.0 candidate confirmed rows/s.
Pooled nearest-rank p50/p90/p95/p99 request latencies (16 samples per arm) are
288.2/476.1/493.6/493.6 ms versus 204.6/316.0/324.6/324.6 ms. **These are
descriptive figures, not a qualified throughput or tail gain:** baseline
throughput falls 34.2% between controls, and candidate throughput also varies
substantially. Sample p95/p99 are maxima, not population tails.

At the recorded 100 ticks/second, baseline process CPU increases from 1.99s to
3.03s across controls, alongside a 1.99s to 3.03s wall-window increase. GC time
changes only from about 97ms to 125ms. GC or descheduling alone therefore does
not explain the variance; its cause is not established. Do not rerun unchanged
until favorable numbers appear or attribute the pooled ratio solely to this fix.

## Correctness and scope

All four writers closed successfully. Four separate normal-decoder reader
processes each verified **4,608 recovered rows, 22 core fields and multiplicities**.
Actual JSON-reader acceptance was nine genuine accepts / zero declines per
writer, including warmup; the native stream and encoder were each observed nine
times. Wire bodies were 653,705 bytes, below the unchanged 1MiB limit. Source
postchecks succeeded before success-only retirement of each exact temporary store.
The campaign's terminal receipt is in the workspace evidence run log; adjacent
raw writer/reader/source-check reports preserve the checked outcomes.

This is an instrumented sequential local socket screen with a pre-encoded
client body. The benchmark observes parser selection via Var wrapping; it is
not the separate production config path in Oscope PR #174. This does not prove
mixed/typed/socket all-field equivalence, nested EventsJSON/LinksJSON text,
sustained throughput, S3 performance, or the original direct-SDK 25k/20k targets.
Body/parser and Durable/native phase timers overlap and must not be summed.

## Sources and reproduction

- Official stock Jolt 0.8.23 and native chDB 26.9.0, checksum checked by the script.
- Configured data.json `0f51b99101bc5e840f957c073f87b6f877309a25`.
- Published codec/reader `43291a2fbcc6f1f855103c1db4264a3c13d618c1`.
- Baseline SDK `26186ce79941e84e0cc67eb148dd8dd43b4e1880`.
- Candidate SDK source now preserved at
  `casselc/otel@5b15abe05ebb4bf45b81e445985ba4001dc2a488`, with the measured
  `any_value.clj` unchanged. It composes the existing pinned-consumer features;
  it is not the independently merged main-only fix. Main PR #65 merged at
  `5381ecc0a0b9bc74697d280c81a4498140095ce3`, after source review and green CI.
- Fixture source is parent Oscope research head `dfb2f6788df1189ccff5600d17ad034da4246571`
  plus the adjacent runner and an added `:any-value-source` metadata field.

Run `bash research/socket-ingest-20261020/run-sdk-defaults-abba.sh NEW_ABSOLUTE_DIR`
from this checkout. The script requires the local candidate checkout at the
documented path, checks selected SDK/codec sources and immutable source hashes,
and uses separate baseline-configured recovery processes. It runs only one
native process at a time. No local build/review ran concurrently with timed windows.
`bb research/socket-ingest-20261020/summarize-sdk-defaults.clj REPORT_DIR` recomputes
the descriptive table and pooled percentiles without rerunning the workloads.

Next optimization candidates remain per-AnyValue lazy arm classification,
repeated map-field lookups and decimal/ID validation. Measure them against
unchanged wire validation and budget/error semantics; do not assume JSON byte
encoding still dominates the now-faster inbound pipeline.
