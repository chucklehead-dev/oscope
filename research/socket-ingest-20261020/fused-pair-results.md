# Fused scanner: confirmed socket follow-up

Both matched arms use stock Jolt 0.8.23, chDB 26.9.0, data.json0f51b991 and
the explicit span codec. Only the candidate replaces the private scalar finder
during synchronous request parsing. This is a benchmark seam, not a supported
production/global parser registration. Existing decode/error/cursor behavior
and all publication/ACK policies remain unchanged.

Eight measured 512-span requests follow one warmup. Both writers and independent
readers exited zero; each reader checked all 4,608 rows, 22 core fields and
multiplicities. Receipts were copied/compared and source hashes rechecked before
retiring both successful stores. Raw data is in `fused-pair/`; the runner is
`run-fused-pair.sh`. Nested event/link JSON text is not checked by this readback.

| Measurement | Original finder | Fused finder |
| --- | ---: | ---: |
| Pooled confirmed rows/s | 871.7 | 846.9 |
| Request p50 | 534.1 ms | 528.0 ms |
| Request p90 / p95 / p99 (eight samples: max) | 776.2 ms | 850.7 ms |
| Estimated allocation per confirmed row | 329,015 B | 329,225 B |
| Request JSON parsing total | 2,697.9 ms | 2,126.4 ms |
| Body + conversion + JSON inclusive total | 3,042.3 ms | 2,596.4 ms |

The JSON phase improves about 21%, but pooled throughput falls about 3% and the
maximum sample worsens. Other phases also slowed in the candidate window;
causation is not established by this single ordering. **No end-to-end win is
qualified; do not enable or upstream this experimental wiring on that basis.**
Allocation estimates are coarse complete-window live-heap/collected-byte
deltas, not a per-object profiler. No direct-SDK, sustained, S3 or Rust-relative
target claim follows. Eight samples cannot qualify population tails.

Focused parity/restoration tests passed4tests/778assertions. The selected fork's
Jolt-specific compatibility main also exited zero with all checks passed while
the fused finder was selected. A separate attempt to load the broad JVM test
namespace failed before selection at a lone-surrogate literal unsupported by
Jolt; that attempt is not a passing gate or a candidate-caused regression.

The scanner's loop removes repeated suffix searches but does not remove
generic parser dispatch around collection construction/punctuation. The next
experiment reuses the existing guarded whole-document reader rather than
assuming this smaller isolated parsing improvement meets application goals.

Claude read-only review approved this benchmark/evidence slice with no blockers;
Codex owns the actual execution. The private scanner wiring remains research
only and is not a production parser API or an upstream-ready integration.
