# Minimal embedded dependency profile

Applications that own their own ingress can depend on Oscope's embedded
lifecycle without acquiring the loopback server or viewer libraries. Use the
same repository coordinate with `:deps/root "profiles/embedded"`:

```clojure
{:deps
 {io.github.chucklehead-dev/oscope-embedded
  {:git/url "https://github.com/chucklehead-dev/oscope.git"
   :git/sha "<reviewed Oscope revision>"
   :deps/root "profiles/embedded"}}}
```

The profile exposes the canonical `oscope.embedded` and
`oscope.embedded.query` implementations from `src`. It depends only on OTel,
jolt-chDB, jolt-otel-clickhouse, Malli, and data.json. It does not add
jolt-http or jolt-otel-viewer, start a listener, or provide a second lifecycle
implementation. A consumer's direct dependency selections still have normal
precedence; this profile is not an override mechanism.

The checked-in `test/fixtures/minimal-embedded-app` fixture resolves the
profile, asserts the exact dependency revisions, loads neither
`oscope.server` nor `oscope.embedded.viewer`, starts one SDK owner, retires a
bounded query helper, and closes every owned resource exactly once.

Run its focused graph and lifecycle qualification with:

```sh
jolt -M:test-embedded-profile
```

## Samizdat qualification boundary

The original dependency audit was pinned to casselc/samizdat
`22be90ddf9b05ba8406d6ec231d2748a4da22d8e`. Its application-side versions
included:

- `jolt-lang/http-client` `ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0`;
- `jolt-lang/db` `d85f391ca521da389b935c38f3d78b30eaa23208`;
- Malli 0.17.0; and
- upstream data.json `94463ffb54482427fd9b31f264b06bff6dcfd557`.

The current embedded profile selects OTel
`0c50b0f8254713ce9df8a3f201f345b1854000b8`, jolt-chDB
`3552a2575a96e3c9dd7b495a9b16b1e9c3317eee`, and jolt-otel-clickhouse
`14a2998a27f64a9bff329811461be9157a00c849`. OTel's provider-convergence merge
contains the interruptible upstream HTTP behavior in the integrated
`casselc/http-client` revision
`eab6b78d5957f88690faf6768360572a3f185341`. Its documented consumer migration
must be applied by Samizdat itself because dependency aliases do not propagate.

The database provider is now converged. jolt-chDB's merged main revision uses
the canonical `jolt-lang/db` key at reviewed provider `6db79163`. The updated
`test/fixtures/samizdat-current-graph` selects current merged `casselc/db` main
`96324713` under that same key; the merge contains `6db79163`. Actual
`jolt -Spath` resolution proves that Samizdat's authoritative `db/sqlite.clj`
surface and Durable chDB coexist with exactly one physical `db/**` source root.
The separate `samizdat-pre-convergence-db-graph` fixture pins the old
`io.github.casselc/db@a5bf25d9` and `jolt-lang/db@d85f391c` coordinates as a
causal red control and proves that graph has two provider roots.

This requalification does not migrate Samizdat's HTTP coordinate or qualify
the remote-stall/Langfuse path. The current graph fixture continues to observe
both pre-migration HTTP roots so that residual work is not mistaken for part of
the database result.

## Durable status boundary

`oscope.embedded/status` remains version 1 and reports Durable freshness and
the last successful persistence boundary as `:unavailable`. This slice does
not adopt or expose a Durable freshness/status capability, and it does not
infer freshness from an open connection.
