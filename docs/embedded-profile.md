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

The dependency audit is pinned to casselc/samizdat
`22be90ddf9b05ba8406d6ec231d2748a4da22d8e`. That application selects:

- `jolt-lang/http-client` `ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0`;
- `jolt-lang/db` `d85f391ca521da389b935c38f3d78b30eaa23208`;
- Malli 0.17.0; and
- upstream data.json `94463ffb54482427fd9b31f264b06bff6dcfd557`.

The current embedded profile selects OTel
`0c50b0f8254713ce9df8a3f201f345b1854000b8`, jolt-chDB
`dbc2db22130c7e783739c79bc24691dcbba21906`, and jolt-otel-clickhouse
`14a2998a27f64a9bff329811461be9157a00c849`. OTel's provider-convergence merge
contains the interruptible upstream HTTP behavior in the integrated
`casselc/http-client` revision
`eab6b78d5957f88690faf6768360572a3f185341`. Its documented consumer migration
must be applied by Samizdat itself because dependency aliases do not propagate.

Database providers remain a blocker. jolt-chDB brings `casselc/db`
`a5bf25d9e141e8dcf28d6d07cd053fceb0019563`, while Samizdat brings the
divergent `jolt-lang/db` revision above. Both contribute the same `db.*` and
JDBC provider surface. The checked-in `test/fixtures/samizdat-current-graph`
reproduces both DB roots and both pre-migration HTTP roots with actual
`jolt -Spath` resolution, and the causal qualification test rejects that graph.
Oscope deliberately does not select one implementation under the other's
coordinate.

Until the DB provider converges in its owning library and Samizdat adopts the
merged HTTP coordinate, the combined SQLite plus Durable application fixture
is not qualified. This keeps blocked-request cancellation and authoritative
SQLite state explicit rather than claiming them from a dependency winner.

## Durable status boundary

`oscope.embedded/status` remains version 1 and reports Durable freshness and
the last successful persistence boundary as `:unavailable`. The current
profile pin predates jolt-chDB's proposed public status capability. Oscope will
only populate those fields after a reviewed status API merges and the profile
is repinned to that public commit; it does not pin an unpublished feature
branch or infer freshness from an open connection.
