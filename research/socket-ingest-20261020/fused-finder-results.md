# Scalar fused-finder experiment

After the full native token-reader hook showed increased allocation in the
confirmed socket pair, this narrower experiment replaces only the selected
data.json private `next-string-special` function with a lexical Scheme scan.
It returns one integer index, not a `[decoded-token position]` vector. Existing
JSON decoding, cursor/error behavior and data construction remain unchanged.
This is a **benchmark-only private-var substitution**, not a production API,
global parser installation or claim to have removed the data.json fork.

On official stock Jolt 0.8.23 / Chez 10.4.1, both arms use the app's unchanged
data.json `0f51b99101bc5e840f957c073f87b6f877309a25`. The actual fixed-timestamp
512-span wire fixture has 653,705 characters. Two warmup rounds and twelve
measured rounds alternate forward/reverse order. Complete decoded values match;
initial controls compare the original/fused finder at every valid start offset
in seven strings and compare values or exact error class/message for seven JSON
inputs. They are bounded controls, not the full JSON conformance suite.

| Measurement | Original finder | Fused finder |
| --- | ---: | ---: |
| Parse p50 | 280.50 ms | 178.81 ms |
| Parse p90 | 347.71 ms | 227.78 ms |
| Parse p95 / p99 (12 samples: max) | 441.21 ms | 246.67 ms |
| Estimated mean allocation per document | 45,221,936 B | 45,223,888 B |

This is about 36% lower median parse time with essentially unchanged estimated
allocation. It is not a measured 36% confirmed-throughput gain. Coarse estimated
allocation is the delta of live heap plus collected bytes; reads are separate
snapshots, not an exact allocation profiler. Timings are a single-process
screen, not population tails. A small evidence commit/push occurred during the
process; unlike the confirmed pair, this screen is not a fully isolated run.
The previous native token-reader screen used newer data.json83b0e97, so do not
compare the two candidates as a matched head-to-head result.

The Scheme loop fuses quote/backslash searches and crosses the host boundary
once per located special character. It advances only to the first such
character, rather than searching independent document suffixes. The original
parser remains allocation-heavy; this does not explain or eliminate its other
costs. The next gate is the same confirmed socket/fresh-reader experiment,
followed by a supported library/backend seam and a fuller parity suite before
any production integration. This follow-up experiment is not yet independently
reviewed; the preceding confirmed native-token pair was reviewed separately.

Reproduce from this checkout using the wrapper and stock binary in
`run-pair.sh`, `-Srepro -Sdeps '{:paths ["src" "benchmark"]}'`, then
`research/socket-ingest-20261020/fused-finder-screen.clj`.
