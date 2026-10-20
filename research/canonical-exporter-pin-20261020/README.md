# Canonical exporter adoption in root Oscope

Base: Oscope main `92b876b2d1a436e5fd780dd1da86b77bfa7311fe`.
Root exporter: `60e1f69` -> canonical merge
`6a98b94213648fcf52036d72019281fc37d0165c` (exporter PR145).
That merge has the exact tested/reviewed tree, with all five checks green.

Only root deps, its independent pin oracle/additional real prior-pin control,
and changelog change. No app runtime/config/default or other pin changes. The
separate minimal embedded profile is held unchanged. Experimental codec/cache
options are NOT enabled or automatically resolved in the mixed-signal app.
Exporter issue146 tracks the remaining explicit-codec multi-signal integration.

## Gates

Official stock Jolt0.8.23 / mandatory Chez10.4.1 wrapper. Native socket cases
select cached checksum-pinned chDB26.9.0 rather than silently using26.7.3.

- Focused dependency + export-option namespaces: **31 tests /392 assertions**.
- Before/after effective classpath source-root FREQUENCIES differ only by the
  exact exporter root. Every other selected root is unchanged, including the
  explicitly selected crypto/HTTP/database/ordinary-JSON providers. An in-memory
  changed-provider mutant is rejected. This is a receipt comparison, not a
  runtime proof of every dependency's semantics.
- Real typed standalone/socket/restart fixtures: **5 tests /158 assertions**,
  all children terminal with valid receipts. Source data crossed real loopback
  sockets; includes typed span/log queries and finite/nonfinite metric input.
- Full Durable gate: **131 tests /1,719 assertions** plus **6 actual native
  fixtures /81 assertions**, terminal0. Every actual fixture exited0 with
  valid, settled receipts. The explicit supported-capability and exact26.9.0
  guards ran before the suite, so no skipped native phase counts as coverage.
  Earlier143/failure lines belong to deliberate synthetic unit controls,
  not the subsequent actual native fixture outcomes.

Claude approved the three-file pin/oracle/changelog diff and graph/socket
evidence with no blockers. Its review was completed while the Durable gate
was running and does not claim to have executed or reviewed that later gate.
Codex independently verified its terminal131/1719 +6/81 outcome. No source
change was needed after review. Text is retained in `claude-review.txt`.

No throughput benchmark, new model logic or unchanged exhaustive run. No real
Langfuse/Lemonade request, desktop/GTK or complete app AOT qualification. No
target/population-tail/S3-performance claim. The ~25k p50/~20k p99 goal remains
unmet. Existing Selmer prep-lib warning is unchanged, not a generated-asset
qualification. Exact selected roots and logs are retained beside this file.

## Invocation corrections and retention

Two initial invocations failed before native fixtures and are not counted:
the socket runner needs `JOLT_TEST_CHILD_EXECUTABLE`, not just `JOLT_BIN`; the
Durable capability API returns a map whose `:status` must be compared with
`:supported`. The failed assertion terminated as expected. Corrected Durable
invocation uses an explicit `when-not` guard plus an exact26.9.0 version guard,
so a skipped native phase cannot count as qualification.

For children, set both executable variables to the official absolute binary;
set `JOLT_CHDB_LIB` to the26.9.0 cache artifact, `JOLT_NO_USER_DEPS=1` and
`JOLT_AOT_CACHE=0`, and invoke through the required workspace wrapper with
`-Srepro`. Use `-M:test-typed-socket`; the Durable command uses
`-A:test-durable`, requires `jdbc.chdb.native` and `oscope.durable-test-runner`,
checks `(:status (native/durable-capability))` and `native/chdb-version`, then
calls the runner. Its established outer wall bound is415s plus5s kill grace.

The successful socket parent was terminal0; copied all ten child log/error
files and compared each before retiring only its exact completed log directory
`/tmp/oscope-typed-socket-children-1792474189448-1`. No unique evidence removed.
Durable-run child receipts are separately retained under `durable-children/`;
their parent was terminal0 and every actual child settled before copying.
All twelve copied child files compared byte-for-byte before retiring the
exact completed log-only directory
`/tmp/oscope-durable-native-children-1792474474336-18`. No bulk native data or
binaries are retained in this packet (about148KiB of small evidence).
