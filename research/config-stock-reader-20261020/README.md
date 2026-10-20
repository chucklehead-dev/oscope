# Config-selected HTTP JSON reader qualification

This slice adds default-off `:ingest {:json-reader :experimental-stock}` to
version-2 configuration and `:json-reader` to server startup. Ordinary parsing
and pure in-process SDK capture keep their current defaults. No writer/lease,
WAL, acknowledgement, model logic or exhaustive-check changes.

Canonical base479010c; reader component7ab2bed, parent exporter1e7cc8d and driver
3314b79 remain distinct dependency roots. The optional span and reader aliases
select the same component to avoid profile-order pin conflicts. New loading is
before JDBC/readiness acquisition, but config runtime backend construction can
precede server startup; no claim of zero earlier filesystem effects.

## Executed gates

Official stock Jolt0.8.23, mandatory Chez10.4.1 wrapper, native chDB26.9.0 for
the real capture fixture. The fixed native and compiler artifacts match those
used in the published-reader performance screen. Small actual stdout logs are
retained here, not raw stores, payloads or large compiler artifacts.

- Default/OTLP/server/config/Durable runtime focused regression: **75 tests /
  630 assertions**, zero failures/errors.
- Published-component startup/parity plus new/old runner witness controls:
  **4 tests / 17 assertions**, zero failures/errors. These are not native-storage
  delivery tests themselves.
- Real older Git component98e93e2 has the encoder but no reader namespace;
  explicit reader startup rejected before JDBC, without a mocked capability.
- Actual config-selected standalone socket + separate recovery process:
  **1 fixture / 45 assertions**, zero failures/errors, nested reader terminal,
  settled and valid. Traces, logs and metrics each exercised the real native
  document reader: **3 accepts / 0 declines**, observed after startup reset.
  Existing linked-context flags/state/attributes/text, metric-kind and Durable
  lease/checkpoint assertions remain in the fixture and fresh-reader oracle.

The real fixture sets the mode in its versioned config before materializing
server options. The runner requires that option to arrive unchanged; it cannot
repair a dropped field at handoff. Reader observation wraps the genuine public
attempt and preserves its result. Startup-only probes and three deliberate
declines are rejected; the positive witness uses three actual accepted calls.
The startup-only control supplies exactly three constructor reads, ensuring
that removal of the reset would let the bad witness qualify.

## Reproduce

Use the repository-required Chez wrapper with the qualified stock binary:

```sh
jolt -Srepro -A:stock-json-reader -Sdeps '{:paths ["src" "test"]}' -m oscope.json-reader-native-test
jolt -Srepro -A:stock-json-reader -Sdeps '{:paths ["src" "test"]}' -m oscope.stock-reader-runner-test
jolt -Srepro -A:test-durable:stock-json-reader -m oscope.export-options-native-test-runner stock-reader standalone
```

The last command additionally needs qualified `JOLT_CHDB_LIB` and absolute
`JOLT_BIN` for its independent reader, as in the existing native fixture jobs.
CI repeats these gates and the actual old-component check.

This is functional, bounded qualification, not performance measurement of the
production config path, every value shape, approved typed socket ingestion,
whole-app native packaging, S3 or population tails. The separate published-API
benchmark's1.66x first local pair is not automatically this config path's result.
Independent review and exact-head full CI remain gates before merging.

## Independent review

Claude read-only review approved implementation4ada323 and the proposed PR
text with no blockers. It checked default/lazy loading, v1 closure and both
v2 launcher handoffs, pre-JDBC/readiness ordering, UTF-8 limit ordering, genuine
reader witnesses, fallback/value behavior and claim scope against the diff and
small logs. It did not execute checks; Codex owns the gates above. The review
explicitly retained the caveat that config backend construction can precede
server startup, and did not promote this fixture into performance/conformance,
S3 or whole-app native-packaging qualification.
