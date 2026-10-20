# Root Oscope stack on stock Jolt

Parent: canonical main `b6ac5c10ab04b9ff4504d895ba2280359e88a0d2`.
This slice changes root dependency declarations, pin/provenance tests and the
changelog only. No application runtime, configuration, storage, mixed-signal
default, schema or persistence acknowledgement contract changes. The explicit
span-only experimental codec is NOT enabled in the mixed-signal collector.

## Qualified root

- Official Jolt 0.8.23, binary SHA256
  `7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94`;
  local invocations use the required Chez 10.4.1 wrapper.
- SDK `26186ce79941e84e0cc67eb148dd8dd43b4e1880`.
- Driver `3314b79e8a234fafdf5231439a0323c878a40040`.
- Exporter `60e1f69bbb06766c36ca9ef0c34af51a97e69c34`.
- Explicit crypto `649c993780a0f269938c9c6afa3bae7e9709bb48`.
- Ordinary JSON, database provider, HTTP client and viewer pins unchanged.

Exporter PR #142 passed all checks and merged into its stack parent as
`1a8a0c4d43c2e330118dc5b10e3b9b69d673b041`, NOT canonical main. Its tree is
identical to the tested/pinned exporter `60e1f69`. This app can resolve the stable
published SHA; final library-main stack integration remains separate work.

## Terminal local gates

- Initial focused dependency/floor/encoding/config checks: **64 / 630**.
- Canonical headless suite, after adding previous-pin controls:
  **351 tests / 3,452 assertions**, terminal 0. This includes actual native
  minimal-profile acceptance, but that separate profile still uses its OWN
  unchanged SDK/driver/exporter pins, not this new root graph.
- Real typed standalone/socket/restart fixtures: **5 / 158**, terminal 0, with
  every child receipt valid and terminal. Covers span/log typed readback and
  finite/nonfinite metric handling on actual loopback sockets.
- Canonical Durable runner: explicit real `durable-capability == :supported`
  assertion BEFORE running, to prevent a skipped native phase counting as
  qualification. Pure controls: **131 / 1,719**, then all **6 actual native
  fixtures / 81 assertions**, terminal 0; every actual fixture receipt valid,
  terminal and settled. Includes standalone/embedded SDK capture, approved
  manifests/live queries, fresh-reader recovery, lease fencing and local/remote
  trace identity against a test receiver, NOT real Langfuse.
- Final focused checks after explicit crypto pin/new oracle:
  **66 / 643**, terminal 0.

Native gates use cached chDB 26.7.3, ordinary configured JSON and the exact
stock executable in parent/children. No local SDK/driver/exporter overrides,
remote Lemonade/LLM run, new benchmark, throughput claim, new model logic or
local exhaustive check. The pre-existing Selmer prep-lib warning remains;
these gates do not attest to every generated Java asset or GTK build.

## Crypto: do not discard an existing platform fix

The first graph receipt selected the driver fork649c993 transitively, not the
exporter's direct upstream5effcc8 choice. Source comparison shows only native
Intel Homebrew search-path metadata and a test differ; Linux implementation
files are identical. Making649c993 explicit preserves that already-selected
provider instead of silently downgrading it or testing a different stack.

The full headless/socket/Durable gates ran before the explicit declaration.
Actual source-root FREQUENCIES before/after are equal, not merely the declared
coordinates, and final focused checks ran afterward. The committed checker
recomputes that receipt comparison (not fresh execution):

```sh
bb research/published-stock-stack-20261019/verify-source-roots.bb \
  research/published-stock-stack-20261019/before-crypto.path \
  research/published-stock-stack-20261019/after-crypto.path
```

Its in-memory changed-provider negative control must reject. The app test also
checks exactly one selected crypto root and its exact SHA. These checks attest
the dependency artifact selection, not all cryptographic semantics or a
security proof.

Latest canonical crypto `e6981b8` already has a better Mac/Windows loader and
ranged-update fixes among 18 intervening commits; replacing the fork requires
separate qualification. Recorded in chucklehead-dev/jolt-chdb#300. Our Jolt
Durable file-digest path still uses explicit EVP prefix staging, so the old
MessageDigest range behavior is not evidence of a current WAL bug.

## Review and boundaries

Claude's four-file source review found no hidden runtime/default change. It
correctly observed the new app test alone does not compare the pre/post
classpath frequencies. That stronger assertion is separately executed and
recomputable from the real saved receipts here; do not describe the pin oracle
itself as that proof. Equal ordinary/woven historical-driver constants are
unchanged intentional qualification lanes, not this root's selected driver.

The separate minimal embedded profile, complete desktop/GTK packaging,
application fast-codec options, real Langfuse and ~25k/~20k throughput targets
remain outside this slice. Revision B overlap type/scope approval is still
pending before new model logic. Do not infer that approval from an automatic
goal continuation or this successful dependency qualification.
