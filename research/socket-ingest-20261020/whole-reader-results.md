# Recovered whole-document reader on stock Jolt

This reuses the existing clean `feat/guarded-whole-string-reader-20261005`
candidate at **bad53dc6eb44210fdc9ad8aa257f3d46f225c3b3**; it does not rewrite
the parser or promote it into application defaults. Source is in the adjacent
`data-json-whole-string-reader-20261005` worktree. Both arms select that local
dependency explicitly. It is not the app's default JSON pin or master83b0e97.
The runner verifies the exact source HEAD, clean tracked files and before/after
hashes of the parser, loader, String token decoder and whole reader.

The existing opt-in backend works on official **stock Jolt 0.8.23** with the
mandatory Chez10.4.1 wrapper. It calls existing runtime collection helpers and
stock number conversion, and reuses the existing native escaped-token decoder.
No custom compiler helper, default writer replacement or global parser is
installed. It remains experimental, source-mode only: neither library/app
standalone builds nor general JSON conformance are qualified by these checks.

## Pure parser screen

The exact 512-span fixed-timestamp wire fixture (653,705 characters) is parsed
by both backends in one process, alternating forward/reverse order. Two warmup
rounds precede12 measured samples. Full decoded values match every sample; a
separate tracked invocation proves the whole backend accepts the actual fixture
rather than silently declining. `whole-reader-screen-results.edn` has raw samples.

| Measurement | Portable on bad53dc | Whole reader on bad53dc |
| --- | ---: | ---: |
| Parse p50 | 371.15 ms | 14.38 ms |
| Parse p90 | 443.92 ms | 24.70 ms |
| Parse p95 / p99 (12 samples: max) | 447.47 ms | 38.15 ms |
| Estimated mean allocation per document | 45.22 MB | 11.13 MB |

This is approximately26x lower median parsing time and75% lower estimated
allocation in the screen, not a26x application throughput claim.

## Confirmed local socket pair

`run-whole-reader-pair.sh` runs one control then one candidate process, each with
one warmup and eight measured512-span requests. Source is identical in both;
only the handler-scoped whole-reader binding differs. Request timestamps are
generated independently per process, so the arms are matched fixture shapes,
not byte-identical payloads. Both writers exited zero, closed cleanly, and then
independent default-decoder readers checked **4,608 rows, all22 core fields and
multiplicities per arm**. Both copied receipts and source postchecks passed
before the exact successful temporary stores were removed. `whole-reader-pair/`
contains the raw receipts. Nested EventsJSON/LinksJSON text is not in this
readback oracle; separate earlier record/link tests cover that slice.

| Measurement | Portable on bad53dc | Whole reader on bad53dc |
| --- | ---: | ---: |
| Pooled confirmed rows/s | 987.2 | 2,019.6 |
| Request p50 | 517.1 ms | 235.7 ms |
| Request p90 / p95 / p99 (eight samples: max) | 555.1 ms | 322.6 ms |
| Estimated allocation per confirmed row | 329,595 B | 263,536 B |
| Request JSON document time, total | 2,273.5 ms | 114.8 ms |
| Body consumption + conversion + JSON, inclusive total | 2,628.2 ms | 496.3 ms |

The first matched pair shows2.05x pooled confirmed throughput and about20%
lower estimated allocation. It is **one small local pair**, not sustained or
population-tail qualification. Parser and body phases overlap; do not sum them.
Allocation uses coarse separate live-heap/collected-byte snapshots over the
complete window, not an exact object profiler or encoder-only attribution.
The remaining2krows/s HTTP path is not a ceiling on the original direct-SDK
Durable25k p50/20k p99 target. S3, concurrent/mixed/typed traffic and Rust/bb/JVM
comparisons are separate. No ACK, lease, WAL, recovery or body-limit policy changed.

## Gates and next steps

The recovered reader's existing focused suite passes **8tests/127assertions**
on this stock runtime: supported-path activation, callback/Reader bypass,
fallback/error/duplicate-key parity, Unicode, depth fallback and numeric classes.
These are bounded regression checks, not complete JSON conformance. The existing
candidate's trusted backend and fallback contracts still require review and
broader consumer/property tests before production integration.

Next: independently review this benchmark/evidence, replicate with explicit
per-document backend acceptance counts, and package/reuse the existing parser
behind an explicit library API without adding a compiler-fork requirement.
Then qualify startup/failure/standalone paths and instrument the remaining
body conversion and OTLP decoding costs. Do not silently repin master or discard
the current writer, interop, cross-runtime or direct-SDK baseline work.

## Review and additional regression gate

Claude read-only review approved the benchmark selection, raw-report arithmetic,
phase/counter scope, dependency checks, recovery gates and interpretation with
no blockers. Its approval is **research-evidence only**, not production approval
for the parser core. Codex owns the executed gates. The reviewer specifically
confirmed that handler-invocation counts are not native acceptance counts.

The recovered branch's separate token-reader regression suite also passed on
stock Jolt: **17 tests / 1,070 assertions**, zero failures or errors. This is
additional compatibility evidence, not a substitute for broader whole-reader
conformance and packaging qualification.
