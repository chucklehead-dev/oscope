# Existing native reader: confirmed local socket pair

Both arms use stock Jolt 0.8.23, chDB 26.9.0 and data.json
`83b0e97d88a322e870c297c8e14261b727572d73`. The baseline is the explicit span
codec with portable JSON parsing; the candidate explicitly binds the existing
native token reader **inside the handler**. Application defaults/pins are
unchanged. Run with `bash research/socket-ingest-20261020/run-reader-pair.sh`.

The sequential control/candidate pair uses eight measured 512-span requests
after one warmup. It is diagnostic, not a sustained/tail or target gate.
Both exact writer processes exited zero and separate readers then recovered
4,608 rows each, checking all 22 core columns and multiplicities. Copied receipts
were compared before each exact temporary store was removed. Both source hash
postchecks passed. Raw reports are in `reader-pair/`; these complement the earlier
linked-span tests and do not check the nested EventsJSON/LinksJSON text here.

| Measurement | Portable reader | Native token reader |
| --- | ---: | ---: |
| Pooled confirmed rows/s | 1,013 | 1,048 |
| Request p50 | 497.4 ms | 446.7 ms |
| Request p90 / p95 / p99 (eight samples: max) | 558.2 ms | 579.7 ms |
| Estimated allocation per confirmed row | 329,657 B | 412,614 B |
| Request JSON document time, total | 2,140.9 ms | 1,958.9 ms |
| Body consumption + String conversion + JSON, inclusive total | 2,500.3 ms | 2,308.9 ms |

The apparent pooled throughput gain is only 3.5%; the worst sample is worse and
estimated allocation rises about 25%. There is no qualified tail or substantial
end-to-end win, and this is not grounds for enabling the hook by default.
The small count and one ordering do not establish statistical significance.
Allocation uses separate coarse live-heap/GC-byte snapshots over the complete
window, not an exact allocation profiler or encoder-only attribution.

The handler-scoped JSON phase is about 53% of the baseline window; body handling
adds about 9% outside it. These phases overlap and must not be summed. This
supports continuing parser/materialization work, but does not identify a proven
allocation source. Native token return vectors and their validation are one
specific hypothesis; the parser also retains generic character dispatch and
collection construction. A smaller fused special-character finder can test a
linear scan without paying a full decoded-token result structure per string.
Do not extrapolate this HTTP workload into a direct-SDK or Rust throughput limit.

## Counter correction and failed probes

The first instrumented pair stopped at the document-count assertion and is not
a passing result. A separate two-row probe established four JSON reads during
one request window: counting all `json/read-str` calls included reads outside
the request parser. The successful revision scopes that counter with a dynamic
marker around `parse-json-body`. It requires exactly eight request-document
observations in each arm, nine codec/native inserts each, and nine explicit
reader-request invocations only in the candidate. The actual parser return
values and exceptions are preserved. Failure stores remain separate from the
successful pair and are not silently counted or retried as successes.

No ACK, schema, durability, ownership, replay or receiver body-limit policy was
changed. This benchmark-only reader selection is not a production setting or a
global default. Full reader/library and standalone packaging gates remain
separate from this socket check.

## Separate regression and review gates

The existing native-reader/string-reader/string-scan suite passed on the same
stock runtime and JSON revision: **15 tests / 963 assertions**, zero failures
or errors. It includes exact Unicode/error/cursor controls, overlapping binding
isolation, and a linear-work oracle rejected by an actual prefix-rescan mutant.

Claude's read-only review approved the benchmark diff and report with no
blockers. Codex owns the executed gates and source postchecks. One reviewer
sentence incorrectly inferred that failed stores were discarded: the failure
stores are retained, exactly as the report and runner say. Only successful,
independently recovered stores were retired. Review was source/report-only,
not an independent execution or recomputation of checksums.
