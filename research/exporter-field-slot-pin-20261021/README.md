# Root exporter field-slot integration

Base Oscope `2615d30e28ff9c2a58197187c72622e5a551f6d1`; update ONLY the root
exporter from `1e7cc8d0a821e589ec087774d118effcaab1d234` to merged exporter
PR157 `a6cbf9390d1521026e5da5d2ea3cfc0b8916ae2b`. Preserve the direct SDK
`3366a332`, driver `3314b79`, ordinary JSON `0f51b991`, codec aliases `7ab2bed`,
crypto/provider pins, runtime floor, storage/encoding/configuration defaults.
No application implementation, persisted format or lifecycle/model change.

The separately pinned `profiles/embedded` remains unchanged. Root
`oscope.embedded/start!` and that minimal dependency profile are NOT the same
dependency authority. Minimal-profile refresh stays in #167; stacked PR144 is
not implicitly merged or approved by this root update.

## Local checks executed

Official stock Jolt0.8.23, SHA256
`7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`,
mandatory Chez10.4.1 wrapper. Child `JOLT_BIN` and PATH explicitly select that
same executable, not installed plain Jolt0.8.10. No compiler rebuild or timing
benchmark. Native stock-codec cases explicitly use cached libchdb26.9.0.
Headless cases use each graph's declared native package, including the old
minimal profile's26.7.3 package.

| Gate | Result |
| --- | --- |
| Current-main headless baseline |361tests /3539assertions pass|
| Candidate headless |363 /3560 pass|
| New witness controls |1 /18 pass|
| Witness controls with assertions disabled |1 /18 pass|
| Actual typed SDK capture/live query |1 /8 pass;1 post-startup field-slot result|
| File-backed embedded SDK capture + fresh reader1 |1 /18 pass;reader terminal/settled/valid|
| Standalone HTTP capture + fresh reader0 |1 /45 pass;reader terminal/settled/valid|
| Native HTTP JSON reads + fresh reader0 |1 /45 pass;3 accepted /0 declined document reads|

The extra headless assertions are18 witness controls and3 exact prior-exporter
resolution controls. Existing resolution tests confirm root SDK3366a332 still
wins over exporter transitive SDK, with the expected HTTP/provider selections.
The root graph is not silently upgraded to exporter-own SDK6cd57c86.

Real red/green:before changing deps.edn, the new native probe with the actual
prior exporter rejected with the fixed unavailable-field-slots error, before
application storage acquisition. After the pin update, the same probe observes
one real selected result during capture and the existing typed live query passes.
Startup/constructor-only calls, missing projectors and false/nil declines are
rejected by pure controls. Positive output is numeric only; no spans, credentials
or persisted data are logged by the observer. It is not a timing instrument.

## Important coverage boundary

Native fixture3 installs an approved Boolean manifest, performs actual SDK
capture and checks typed value/status via the live source. It uses an in-memory
object backend. It DOES NOT independently recover that typed capture.
Fixtures4/2 separately use file-backed stores and independently joined fresh
reader processes; they verify other multi-signal content. Do not combine these
successes into a claim of typed physical-field recovery. That existing
application-level composition gap is tracked in
[#177](https://github.com/chucklehead-dev/oscope/issues/177).
The exporter library's older-stack full physical-row recovery evidence remains
separate from this consumer integration.

No new throughput/p50/p90/p95/p99/S3/Langfuse/Samizdat/packaging or zero-allocation
claim. Defaults are not switched to the experimental codec. No unchanged
exhaustive or model-checker run; there is no new protocol action or ACK meaning.

## Failed launch/configuration attempts retained

- First baseline command exited127 before Jolt:an unquoted inherited Windows
  PATH with spaces split the env assignment. Quoted PATH fixes the launcher.
- First actual baseline forced26.9 globally, causing the OLD minimal profile's
  timestamp gate to reject (361tests,3537passes,2failures). This was the wrong
  native/runtime pairing, not an exporter regression. Removing JOLT_CHDB_LIB
  lets that graph select its declared26.7.3 package; full baseline then passes.
- First assertions-disabled invocation tried to analyze clojure.test/run-tests
  before loading that namespace inside the binding form. It stopped before
  tests. A separate require of clojure.test before the binding fixes selection.

All corresponding logs are retained, not presented as passing tests. The typed
probe's initial docstring called it a capture/recovery fixture; source inspection
corrected that label to live-query only (no executable observer change). This
note and issue177 explicitly preserve the distinction. Final-head hosted checks
must execute the current source before merge; local successes do not stand in
for those checks.

## Reproduce / retention

From root, prefix the pinned stock CLI with
`/home/chuck/ai-src/tools/jolt-with-chez-10.4.1`:

```text
jolt -Srepro -M:test-headless
jolt -Srepro -A:test-durable:stock-span-codec -m oscope.field-slot-native-probe
jolt -Srepro -A:test-durable:stock-span-codec -m oscope.export-options-native-test-runner stock-codec embedded
jolt -Srepro -A:test-durable:stock-span-codec -m oscope.export-options-native-test-runner stock-codec standalone
jolt -Srepro -A:test-durable:stock-json-reader -m oscope.export-options-native-test-runner stock-reader standalone
```

Set JOLT_BIN/PATH for children as above; use26.9 only for the current-root native
cases, not globally across the old minimal profile. Existing fixture controllers
join their readers and retire successful stores through their established gates.
No build/binary/new native asset was created. Small original logs at
`/home/chuck/ai-src/evidence/oscope-exporter-pin-20261021.ldxeRHQs`;
copies below are for public synthetic qualification evidence. The selected
pre-native source manifest predates the docstring-only correction and is not
complete transitive compile-time attestation.
