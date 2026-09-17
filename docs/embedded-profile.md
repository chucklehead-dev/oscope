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
profile and asserts the exact dependency revisions. Its application coordinate
selects converged `casselc/db` `96324713` under the canonical `jolt-lang/db`
key. In one fresh Jolt process it opens a real SQLite file for authoritative
application state and a real local-POSIX Durable chDB writer for telemetry,
installs an approved typed span manifest, starts one SDK owner, emits and
exports one span through independent local and remote pipelines, and reads it
back locally through a bounded typed query while a hermetic remote OTLP peer
withholds its response. A second stalled peer proves that the application's
canonical HTTP provider still returns a blocked request promptly when its
thread is interrupted. The status snapshot remains bounded and redacted while
both pipelines are live. Shutdown retires the query first, cancels and records
the failed remote delivery without replay, shuts down the SDK terminal action
once, retires its Oscope source, checkpoints and closes Durable, and returns
before the fixture closes its outer SQLite connection. Repeated query and
embedded stop calls prove idempotence; the parent test removes chDB scratch
only after the anchored native child process exits.

The fixture loads neither `oscope.server` nor `oscope.embedded.viewer`. The
profile still supplies no listener or viewer dependency. It models Samizdat's
required canonical HTTP-coordinate migration but does not change Samizdat.

Run its focused graph and lifecycle qualification with:

```sh
env JOLT_CHDB_LIB=/path/to/chdb-26.7.3/libchdb.so \
    JOLT_BIN=/path/to/jolt-containing-strict-utf8-and-append-range-fixes \
    jolt -M:test-embedded-profile
```

The outer test runner may remain the project's baseline Jolt, but `JOLT_BIN`
must select the qualified runtime used by the fresh native fixture process.
Hosted CI pins that runtime to public `casselc/jolt` `2d39e854` and installs
the profile's checksum-pinned Linux x86-64 chDB 26.7.3 asset before running the
aggregate. This slice does not claim macOS behavioral qualification.

## Samizdat qualification boundary

The original dependency audit was pinned to casselc/samizdat
`22be90ddf9b05ba8406d6ec231d2748a4da22d8e`. Its application-side versions
included:

- `jolt-lang/http-client` `ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0`;
- `jolt-lang/db` `d85f391ca521da389b935c38f3d78b30eaa23208`;
- Malli 0.17.0; and
- upstream data.json `94463ffb54482427fd9b31f264b06bff6dcfd557`.

That audited Samizdat revision still points the `jolt-lang/db` key at the
`jolt-lang/db` repository. Samizdat itself must repoint that same key to the
merged `casselc/db` repository before combining its authoritative SQLite state
with this Durable profile. The fixture below models that required migration;
it is not the as-pinned `22be90d` graph. The runnable minimal application uses
the same canonical-key repoint for its real SQLite-plus-Durable process.

The current embedded profile selects OTel
`4d61f8e921d1310bc7ba39d7208cc38ac14a3215`, jolt-chDB
`95d7b2b31c95e007d5065e3950deb1869e2d0f8a`, and jolt-otel-clickhouse
`14a2998a27f64a9bff329811461be9157a00c849`. OTel's provider-convergence merge
contains the interruptible upstream HTTP behavior in the integrated
`casselc/http-client` revision
`eab6b78d5957f88690faf6768360572a3f185341`. Its documented consumer migration
must be applied by Samizdat itself because dependency aliases do not propagate.

The provider lineage needed by the modeled graph is converged. jolt-chDB's
merged main revision uses
the canonical `jolt-lang/db` key at reviewed provider `6db79163`. The updated
`test/fixtures/samizdat-converged-db-graph` models Samizdat's required repoint
to merged `casselc/db` main `96324713` under that same key. Qualification uses
the resolved local git checkout to verify that `96324713` descends from
`6db79163`. Actual `jolt -Spath` resolution then proves that the authoritative
`db/sqlite.clj` surface and Durable chDB coexist with exactly one physical
`db/**` source root.
The separate `samizdat-pre-convergence-db-graph` fixture pins the old
`jolt-chDB@dbc2db22` dependency (which brings
`io.github.casselc/db@a5bf25d9`) beside `jolt-lang/db@d85f391c` as a causal red
control and proves that graph has two provider roots.

This requalification does not migrate Samizdat's repository. The runnable
minimal application selects the reviewed provider under Samizdat's canonical
key and qualifies the local plus stalled-remote contract without requiring a
live Langfuse service. The converged-DB fixture intentionally
continues to observe both pre-migration HTTP roots as a tripwire so that
residual work is not mistaken for part of the database result.

## Durable status boundary

`oscope.embedded/status` remains version 1 and reports Durable freshness and
the last successful persistence boundary as `:unavailable`. This slice does
not adopt or expose a Durable freshness/status capability, and it does not
pin an unpublished status branch or infer freshness from an open connection.

## Listener readiness

Both `oscope.embedded.viewer/start!` and `oscope.server/start!` accept an
optional `:readiness` map. Without it, startup and shutdown remain unchanged.
The minimal library profile does not load either listener.

```clojure
(def launch-id (str (random-uuid)))
(def listener-state (atom nil))

(viewer/start! owner
  {:port 0
   :readiness {:instance-id launch-id
               :publish! #(reset! listener-state %)
               :file "/tmp/my-app-readiness/listener.edn"}})
```

`:publish!` is a callback receiving a small map. An atom adapter can use
`reset!`, as above; an existing channel can use an application-owned adapter
such as `#(clojure.core.async/put! readiness-channel %)`. Use a bounded buffered
channel and arrange consumer ownership yourself. Callbacks must return promptly;
they run on the caller's startup/shutdown thread, not a new Oscope worker.
At least one callback or file sink is required. Only `:publish!`, `:file`, and
`:instance-id` are accepted. A file requires an explicit fresh launch identity
(1–128 ASCII letters, digits, `_` or `-`); callback-only startup generates one
if omitted.

Records have `:oscope.readiness/version 1`, the launch `:instance-id`,
`:storage-mode`, and `:status`: `:starting`, `:ready`, or `:terminal`.
A ready record adds the numeric loopback `:host`, actual bound `:port`, and
viewer `:url`. The transport has bound and registered its accepting listener,
and Oscope has installed its exact numeric authority before publication.
Terminal reasons are `:stopping`, `:closed`, or `:startup-failed`.
No credentials, dbspec, telemetry values, or Durable freshness are included.
The embedded owner is Durable; standalone mode is `:durable` only when its
Durability callbacks are configured, otherwise `:local`.

The file sink currently supports Linux x86-64 only. Its dedicated containing
directory must be owned by the current user and have mode `0700`; the existing
parent must be a real directory. Oscope creates the dedicated directory when
absent, refuses unsafe targets, and verifies mode `0600` for lock, temporary,
and published files. Publication uses same-directory POSIX atomic replacement
without pre-deleting the old file, followed by file/directory persistence
boundaries. A nonblocking lifetime lock rejects another live owner before any
database, query worker, or listener acquisition. A crashed owner releases its
lock through process exit; a new launch replaces its stale record with
`:starting`. Do not share this directory with the managed configuration store
or another readiness file.

Launchers must accept only their expected launch identity's `:ready` record
and confirm current listener liveness; a file alone never proves a process is
still alive. Shutdown first attempts to mark readiness terminal. It reports
`:closed` only after owned listener/workers/resources retire and terminal
publication succeeds. Sink failure returns bounded `:closing` /
`:publishing-readiness`; repeat `stop!` retries publication without closing
already retired resources again. An ambiguous lock-descriptor close failure
returns `:closing` / `:readiness-claim-release-unconfirmed`, with
`:retryable? false` and the fixed `:oscope.readiness/claim-release-unconfirmed`
error type. It is not a publication failure or confirmation that the claim
remains held: the close may already have happened. Further calls keep this
honest unresolved result and never close a possibly reused descriptor again.
Terminate the owning process rather than trying to repair it by repeating stop.
Publication callbacks may synchronously call stop, but cannot republish ready
after terminal retirement begins. A nested stop cannot release the claim twice.

Startup callback/file publication failure revokes request authority and rolls
back acquired resources in order. Successful rollback rethrows the original
startup error. If rollback or terminal publication remains incomplete, the
opt-in path instead throws a cause-free
`:oscope.readiness/startup-cleanup-incomplete` error with a bounded operation
and an opaque `:retry-stop!` capability in `ex-data`. The application must own
and call that capability while cleanup remains retryable, until it reports
`{:status :closed :phase :closed}`, or terminate the owning process. A result
with `:retryable? false` requires process termination; it cannot safely converge
through another close attempt. Successful rollback steps are not repeated.
Do not serialize this ownership capability as configuration or diagnostics.
