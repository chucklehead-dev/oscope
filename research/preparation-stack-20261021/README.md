# Canonical preparation-capable dependency stack

Base Oscope main: `5963e52489bd2be340baac255dcb4eb287034bb9`.
Final selected dependencies:

- OTel `38278ab4cf3b7bad7ed817170ca7e380122068b3`.
- chDB `312812f0dfeff5498a6d1aa29b0476a97d8b65f3`.
- Exporter `e5190e8573328ddc03912da227e5f48d00bab6d8`.

These are canonical merged revisions, not experiment branch pins. Codec and
explicit crypto-provider choices remain unchanged, as do the separate minimal
embedded profile and historical producer qualification pins. No application
source or configuration/processor default changes. **Overlap is not enabled.**

## Local verification

Official stock Jolt 0.8.23, mandatory Chez 10.4.1 wrapper; native runs explicitly
selected libchdb 26.9.0. No aspect compiler build or throughput run was performed.

- Final canonical pins: dependency-resolution, runtime-declaration and export
  option suites pass **44 tests / 546 assertions**, zero failures/errors.
- Prior candidate with exporter PR head `a12c6f45368e359c07e6f8f7af088583115e1cec`:
  full embedded namespace **32/705**; real embedded mixed-signal capture **1/18**
  plus fresh-reader receipt; real standalone socket ingestion **1/45** plus
  fresh-reader receipt. Native fixtures observe the real explicit codec and
  stream calls, not just construction. Nested readers were terminal/settled
  and their checked receipts valid.
- Exact GitHub tree queries for exporter PR head `a12c6f4` and canonical merge
  `e5190e8` both returned `a708569fe6b5574f50aa2eb696da28d7bef8aefa`.
  Consequently the native-tested exporter source, dependencies and resources
  are identical to the final merge pin. Those runs still used the PR-head
  coordinate; they are not described as newly executed final-coordinate runs.

Reproduce the final unit gates from this checkout with the explicit stock CLI,
`JOLT_NO_USER_DEPS=1`, `JOLT_AOT_CACHE=0` and `JOLT_BIN` set to that same CLI:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 "$JOLT_BIN" -Srepro \
  -Sdeps '{:paths ["src" "test"]}' -e \
  "(require 'clojure.test 'oscope.dependency-resolution-test 'oscope.runtime-floor-test 'oscope.export-options-test)
   (let [r (clojure.test/run-tests 'oscope.dependency-resolution-test 'oscope.runtime-floor-test 'oscope.export-options-test)]
     (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

Native commands used `-A:test-durable:stock-span-codec -m
oscope.export-options-native-test-runner stock-codec embedded` and then
`stock-codec standalone`, with the same explicit executable/provider environment.
The existing fixture owns scratch cleanup after joined readers. These are
correctness/readback gates, not local/S3 performance or complete application
qualification. Hosted CI and exact final diff review remain separate gates.

The final four-file dependency/changelog diff received a separate Claude
read-only review with no blockers. Codex verified tree identity and ran the
gates; the reviewer did not execute them. Hosted CI remains pending here.

## Remaining application enablement

Tracked in https://github.com/chucklehead-dev/oscope/issues/180.
The present exporter helper is span-only, whereas embedded Oscope shares its
exporter across spans/metrics/logs. Both SDK local-only construction and Oscope
dual pipeline configuration filter out the preparation adapter. Resolve that
composition and its startup settlement accounting explicitly; do not disable
other signals or silently weaken the helper's supported ownership contract.
