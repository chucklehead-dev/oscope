# Root Oscope stock-codec integration

Base Oscope `4c05c63541807f39296c1ebc963d4b304b858e92`.
Tested exporter preflight slice `42cc96c6b04ed86efa980c76b6112f2c459798a4`
(PR148), independent codec `0af1008185cf84577298d3a0a0b2db87b97bddd5`.
PR148 is now merged at `01c991bc4431b1cf90f3a26359c95b3aecf07ceb` after all
checks passed; its tree and parents were checked against the reviewed/tested
slice. The final app pin selects this canonical merge, not the provisional head.

Official stock Jolt0.8.23 binary SHA256:
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`.
Native chDB26.9.0 SHA256:
`a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557`.
Local commands use the mandatory Chez10.4.1 wrapper.

## Changed behavior

Default-off closed encoding flags and optional published-codec alias. Invalid
combinations reject before acquisition. Actual runtime preflight rejects old
exporter/no-codec stacks before JDBC or remote-exporter construction; it is
not saved readiness or persistence evidence. Logs/metrics keep configured JSON.
File config passes the same explicit options through standalone startup;
embedded accepts them at its existing API. Existing storage/shutdown/confirmation
logic is unchanged. No model logic or new concurrent pipeline is introduced.

The separately pinned minimal embedded profile is unchanged, not qualified
for the codec, and must reject the opt-in rather than silently downgrade.

## Local gates

- Focused option + actual dependency-resolution suites: **34 tests /439
  assertions**, no failures/errors. Includes real config roundtrip and early
  rejection controls. Ordinary root resolution remains codec-free.
- Fixture no-op control: **1 test /3 assertions**, rejecting no stream and
  stream-without-codec, accepting actual encoding + observed stream calls.
- Actual prior exporter0fba386 (not a missing-Var mock): both app startup paths
  reject codec opt-in before JDBC; exact selected source is asserted.
- Current exporter with the codec component actually absent from its resolved
  resources also rejects both startup paths before JDBC. Both real controls
  pass through `oscope.stock-codec-legacy-probe` and run in CI.
- Native fixtures below all terminated0; each wrapper makes legacy native JSON
  writer acquisition throw, observes codec + native stream calls, and delegates
  all actual encoding/native writes to the original implementations.

| Consumer | Fixture | Assertions | Codec/stream calls | Recovery |
|---|---:|---:|---:|---|
| Embedded SDK, spans/logs/metrics |4|18|5 /5|Fresh process, terminal/settled/valid|
| Standalone, actual socket traces/logs/gauge/sum/histogram |2|40|1 /1|Fresh process, terminal/settled/valid|
| Embedded approved typed manifest |3|8|1 /1|Live typed ingest/query, no fresh reader in this fixture|

Native command (separate process per consumer, sequential):

```sh
"$JOLT_BIN" -Srepro -A:stock-span-codec:test-durable \
  -m oscope.export-options-native-test-runner stock-codec embedded
# Repeat with standalone and embedded-typed.
```

`JOLT_BIN` and `JOLT_CHDB_LIB` must select the named checksummed artifacts;
the former is also used by independently joined reader children. The existing
fixtures retire their exact synthetic stores only after reader settlement.
Small categorical receipts remain; no telemetry datasets/native binaries are
committed. CI reproduces these modes with pinned official release downloads.

This does **not** establish all physical values for arbitrary telemetry, S3,
Langfuse, complete app standalone packaging, sustained throughput, allocation
or population tails. Earlier exporter-core full-field checks remain distinct.
No new performance number is claimed; ~25k p50/~20k p99 remains unmet.

An initial inline legacy-probe command had an unmatched delimiter and failed
before evaluation; the maintained namespace replaces it. This was not a
product failure or a successful compatibility check.

## Follow-up: actual linked-span records (#149)

The richer socket performance smoke found a real gap beyond the initial tiny
fixtures: the public decoder makes SpanContext records in links; codec0af1008
rejected them before publication (HTTP503). Earlier linked fixtures had used
plain context maps. Do not treat those earlier passes as complete span-shape
interoperability or discard the counterexample.

Exporter PR150 fixes ordinary record traversal without map copies and probes
the SDK's real shape before storage. It merged at1e7cc8d after full green CI,
with reviewed/tested tree/parents verified. The app now selects that canonical
exporter and independent component98e93e2.

The maintained standalone fixture now decodes a real link (flags129, ordered
trace state and false attributes). Its separate reader checks physical link
columns and parsed LinksJSON. Configured JSON and the corrected stock codec
each passed **1 fixture /45 assertions**, with independent reader terminal,
settled and valid. Codec and stream counters reset after startup so constructor
record probes cannot satisfy ingestion evidence; the no-op test now actually
encodes during fake startup and still rejects stream-without-ingestion.

The legacy native-stream local attempt used stock Jolt and correctly rejected
its unavailable compiler-bearing backend before ingestion. That is not a legacy
pass or a record regression. Its existing hosted job supplies the separately
qualified compiler and remains required before app merge. No timing workload
is qualified by these functional fixtures.
