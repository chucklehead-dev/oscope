# Typed Clojure pilot

This is a bounded, opt-in JVM development check over oscope's pure data
contracts. It uses `org.typedclojure/typed.clj.checker` 1.3.0 through
`clojure -M:typed-check`, with Clojure 1.12.5. The checker, annotations, runner,
and controls exist only on the `:typed-check` alias's `script` and `typed`
paths. They are not production dependencies and are not resolved or loaded by
ordinary Jolt aliases.

## Why the execution adapters are separate

Before this pilot, `oscope.query`, `oscope.command`, and `oscope.view-model`
could not load on the JVM because their namespace graph eagerly reached the
Jolt-only `db.jdbc` host shim. The pure planning and validation namespaces now
load without that shim. Embedded execution lives in `oscope.query.chdb` and
`oscope.raw-export.chdb`; `oscope.live` composes those adapters with the same
pure contracts. The query adapter also fails closed if its copied field
allowlist drifts from the pinned chDB explorer.

This separation does not add a JVM chDB implementation, Babashka FFI, or a
second runtime. It only makes the already data-only boundary independently
loadable and checkable. Durable policy/state types are not included because
the implementation tracked by oscope #4 has not landed.

## Checked boundary

`typed/oscope/typed/contracts.clj` externally declares closed types for:

- query signals, fields, windows, scalar/counter/cumulative-histogram
  selections, requests, and versioned plans;
- the five reusable telemetry-expression bucket presets and their optional
  integer widths;
- the versioned query-command variant with the request-ID shapes used by the
  current live, web, native, and Glimmer callers; and
- raw and renderer-independent distribution rows.

The runner explicitly invokes `typed.clojure/check-ns-clj`; loading the
annotations alone is not treated as evidence. It checks the annotation
namespace, `oscope.query`, `oscope.command`, `oscope.view-model`, and the valid
consumer in `typed/oscope/typed/driver.clj`. The annotated production bodies
checked in this pilot are:

- `oscope.query/supported-fields`, `normalize-selection`, `compile-query`, and
  `validate-plan`, plus their annotated constants and exhaustive window helper;
- `oscope.command/query-command`; and
- `oscope.view-model/normalize-rows`.

There is no unconstrained `t/Any` and no `^:no-check` annotation in this pilot
(`t/AnyInteger` is the checker's integer type). The production check
configuration leaves unrelated, unannotated definitions unchecked; the runner
does not claim that `export-command`, the full screen renderer, native execution
adapters, or their helpers were statically verified. The annotated functions'
bodies and the valid consumer are checked against their declared types.

The static types intentionally do not pretend to express every runtime rule:

- integers are shape-checked, while positive limits, maximum epochs, and
  bounded windows remain runtime-validator obligations;
- signal/field compatibility and equality between a plan's selection and
  request remain runtime-validator obligations;
- the typed command slice covers the current integer, keyword, and tagged-vector
  request-ID shapes, while the runtime validator still permits any non-nil
  request ID; and
- raw row signal/field equality with the selected query remains enforced by
  `normalize-rows` at runtime.

Existing Jolt tests remain authoritative for these rules and for every
unchecked namespace.

## Non-vacuous mutation controls

`script/oscope/typed_check.clj` registers exactly nine controls and asserts that
count before checking anything:

- `wrong-command-variant` assigns `:export` where the checked command variant
  requires `:query`;
- `wrong-signal-field` supplies the unsupported `:unknown` signal;
- `wrong-window` supplies `:forever`;
- `wrong-limit` supplies a string limit;
- `wrong-plan-time` supplies a string query end;
- `wrong-bucket` supplies `:30s` where the reusable expression contract accepts
  only `:none`, `:1m`, `:5m`, `:15m`, or `:1h`;
- `wrong-counter-provenance` supplies delta temporality for a cumulative
  monotonic counter;
- `wrong-histogram-temporality` supplies delta temporality for the cumulative
  histogram recipe; and
- `wrong-view-row` supplies a string distribution count.

Each control must produce exactly one structured Typed Clojure type error whose
rendered report contains mutation-specific evidence. Requiring each namespace
happens outside the error-catching block, so missing namespaces and load-time
failures cannot count as successful mutant rejection. Exceptions without the
checker's `:clojure.core.typed.errors/type-error` marker, analyzer crashes, and
unrelated error text fail the runner.

Runtime positive and negative controls remain in `test/oscope/core_test.clj`,
`test/oscope/query_view_test.clj`, and related headless suites. In particular,
the runtime checks still reject out-of-range numeric values and mismatched
signal/field and result-row combinations that the static subset does not model.

## Versions and cost

A completed local run on 2026-09-06 used Typed Clojure 1.3.0, Clojure 1.12.5,
and OpenJDK 25.0.2. The runner reported 9.22 seconds inside the process; the
observed command wall time was 10.7 seconds including JVM startup on a warm
dependency cache. Hosted CI
timing is not yet available and should be recorded from the first workflow run.

Expand only if another pure boundary checks without `t/Any`-heavy or unchecked
escape hatches and adds a mutation that catches meaningful contract drift.
Constrain this pilot if host-bound namespaces or dependent invariants would be
misrepresented. Remove it if maintenance cost repeatedly exceeds the defects
its controls demonstrate.
