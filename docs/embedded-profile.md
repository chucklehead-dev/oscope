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
jolt-chDB, the already-transitive database library, jolt-otel-clickhouse, Malli,
and data.json (six direct dependencies). It does not add
jolt-http or jolt-otel-viewer, start a listener, or provide a second lifecycle
implementation. A consumer's direct dependency selections still have normal
precedence; this profile is not an override mechanism.

## Qualification boundary

### Experimental local Durable streaming

From the repository root, `-A:experimental-durable-stream` selects matching
chDB/exporter/encoder development pins. It does not change this minimal
profile's defaults, and dependency aliases do not propagate to consumer apps.
Consumers must explicitly select the matching reviewed dependency versions.

The experimental alias currently selects exporter `07c0a64b` and data.json
`7fd90244`. These retain the previous stack's typed schema and storage behavior
while reducing row preparation/encoding allocation through private attribute
replay trees and per-factory row cleanup/map visitor closures. They require the explicitly
qualified source runtime; a released version label alone is not qualification.
These development pins do not change default dependencies or establish stable
application throughput, hosted S3 tails, or executable/AOT packaging.
The map visitor saves about 2.56 MB per 10k-row encoded batch on the measured
workload, without an established timing improvement. Its full local Durable
window passed recovery, but narrowly missed the inverse-p99 throughput target;
do not interpret an allocation reduction as stable tail qualification.

On the qualified Jolt source runtime and native package **26.9.0**, configure
the Durable dbspec with `:owned-compact-stream? true`, then give `start!`:

```clojure
{:db-spec writer-dbspec
 :json-backend :native-guarded-byte-batch
 :insert-format :json-compact-each-row
 :owned-statement-output? true
 :datetime64-wire :raw-ticks}
```

The standalone `oscope.server/start!` API accepts the same encoding options.
It also needs a canonical Durable writer dbspec and the usual Durable callbacks:

```clojure
{:port 0
 :db-spec writer-dbspec ; made with :owned-compact-stream? true
 :durability {:checkpoint! jdbc.chdb.durable/checkpoint!
              :flush! jdbc.chdb.durable/flush!}
 :json-backend :native-guarded-byte-batch
 :insert-format :json-compact-each-row
 :owned-statement-output? true
 :datetime64-wire :raw-ticks}
```

Use the matching experimental dependency stack described above. Version-2
configuration files also accept these encoding fields under `:ingest` and
`:owned-compact-stream? true` under Durable `:storage`; see
[configuration](configuration.md#opt-in-durable-streaming-and-raw-nanosecond-ticks).
This does not change the supported default pins or claim
standalone executable/AOT packaging or sustained socket-ingestion throughput.

The exporter writes numeric nanoseconds with the fixed query-local setting in
each INSERT/WAL record. The writer chooses supported owned compact streaming
after normal admission; unsupported shapes retain ordinary execution. Invalid
options and explicit incompatible timestamp packages fail before storage or
typed schema writes. There is no remote destination unless separately configured.

Local capture, typed Boolean filtering and fresh-process full-row recovery are
checked separately from throughput. This does not promise 20k tail throughput,
hosted-S3 performance, standalone builds or replay of older timestamp WAL across
engines. Keep old objects on their qualified engine until migration is checked.

### Default dependency profile

The checked-in profile is the dependency authority. At this revision it pins
`casselc/otel` `8110c12f`, `jolt-chdb` `3e3141fd`,
`jolt-otel-clickhouse` `dfbc9ea6`, `casselc/db` `cb06349c`, and
`casselc/data.json` `97298fd8`; the fixture asserts that resolved graph. Do
not copy older pin values from issue history or from an application's distinct
resolved graph.

The resolved profile tree has been checked for these exact source selections:
one direct chDB/DB/OTel/exporter root wins each older transitive declaration.
The existing **Linux x86-64 local-POSIX** fixture exercises authoritative SQLite
application state, a real Durable chDB telemetry writer, one SDK owner, typed
span emission/readback, ordered shutdown, and a bounded stalled remote OTLP
attempt. It must be rerun on these refreshed pins before that native result is
claimed for this exact profile revision. The fixture and its hosted
qualification are regression evidence, not a portable or service-level
guarantee. The current acceptance record is
[Oscope #77](https://github.com/chucklehead-dev/oscope/issues/77).

In particular, this document makes no claim that the profile has qualified:

- macOS or Windows native behavior;
- S3/object-store Durable recovery or a general native-persistence guarantee;
- a live Langfuse service or delivery to it (the fixture uses a hermetic stalled
  OTLP peer);
- a real Samizdat migration or full application composition; or
- current Durable-view freshness or a last confirmed persistence boundary.

The last item is available only through `oscope.embedded/status-v2`, which
strictly projects the closed, redacted
`jdbc.chdb.durable/persistence-observation` capability while its owned
connection is open. Version 1 remains unchanged and intentionally reports
those values as `:unavailable`. Neither version infers evidence from a
successful checkpoint, an open connection, or a live worker. Physical-provider
ownership has a separate remaining producer/reader qualification boundary in
[Oscope #119](https://github.com/chucklehead-dev/oscope/issues/119).

The profile's public shutdown status separates
delivery results from ownership settlement. Native cleanup requires confirmed
SDK **and** exporter settlement; a failed delivery result is retained even if
cleanup can safely complete. Unknown or still-active owners leave `stop!`
in `:closing` / `:open`. Repeating `stop!` refreshes ownership evidence without
replaying the cached SDK shutdown action. Custom settlement witnesses are
trusted bounded, nonwaiting contracts, not sandboxed implementations.

Startup rollback independently requires SDK and span-pipeline retirement before
the live source or native connection can close. It installs fresh private
construction receipts before each call, so maintained constructors that start
workers and then throw can still clean up acquired owners and positively
untouched exporter faces. Acquired faces are never independently shut down again;
absent claims or other-signal queries cannot authorize cleanup. Foreign or
unobservable constructors intentionally remain unknown and keep native owners open.

Completed rollback rethrows the original startup error. Incomplete rollback
throws `:oscope.readiness/startup-cleanup-incomplete` with an opaque `:retry-stop!`.
Retain that function and retry after owners retire. Calls are serialized, refresh
settlement and do not replay terminal callbacks or completed source/native cleanup.
Diagnostics contain no original error, configuration, handles or witness values.
Truthful permanent bounded/nonblocking witnesses are trusted, not sandboxed;
failed delivery can still allow cleanup when ownership is confirmed. Current
shutdown reasoning does not extend fixture evidence to S3, Langfuse, Samizdat,
macOS, or freshness. Shutdown Quint
does not establish constructor acquisition or face-transfer correctness.

The checked-in `test/fixtures/minimal-embedded-app` fixture resolves the
profile and asserts the exact dependency revisions. Its application coordinate
selects merged `casselc/db` `cb06349c` under the canonical `jolt-lang/db`
key and optimized `casselc/data.json` `97298fd8`. Root and embedded-profile
dependencies explicitly select the same pair, so the old transitive database
cannot select a second time provider by resolution order. Historical DB `96324713`
and pre-convergence causal fixtures remain separate and unchanged. The fixture
checks its own resolved graph; it does not prove an arbitrary production
consumer or independent producer/reader graph has one physical provider (see
[Oscope #119](https://github.com/chucklehead-dev/oscope/issues/119)). In one
fresh Jolt process the
fixture opens a real SQLite file for authoritative
application state and a real local-POSIX Durable chDB writer for telemetry,
installs an approved typed span manifest, starts one SDK owner, and emits one
span through independent local and remote pipelines. It reads that span back
locally through a bounded typed query. The hermetic remote OTLP peer
deliberately withholds its response, so the remote delivery attempt is a
bounded stalled failure, not successful remote export. A second stalled peer
proves that the application's
canonical HTTP provider still returns a blocked request promptly when its
thread is interrupted. The status snapshot remains bounded and redacted while
both pipelines are live. Shutdown retires the query first, cancels and records
the failed remote delivery without replay, shuts down the SDK terminal action
once, and retires its Oscope source, checkpoints and closes Durable only after
SDK and exporter settlement is confirmed. It then returns
before the fixture closes its outer SQLite connection. Repeated query and
embedded stop calls prove idempotence; the parent test removes chDB scratch
only after the anchored native child process exits.

After writer shutdown, the native runner retains a mode-0600 private normalized
head-digest seal. A separate fresh process passes that digest only to
`jdbc.chdb.durable/snapshot-dbspec`, recovers the immutable local-POSIX
snapshot, and emits one fixed `generation-match` receipt bit only after its
readback succeeds. The runner rejects absent, early, duplicate, or malformed
markers, deletes private child output, and publishes no digest, head, path,
payload, endpoint, or credential. A real local mutant then advances and
releases a later lease generation without changing the manifest sequence; the
old seal must fail `:jdbc.chdb.durable/snapshot-head-mismatch` before native
reader recovery. This is same-generation snapshot identity evidence only, not
an S3 freshness, delivery, or general external-reader claim.

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
`8110c12f058e1d6902fe6dad0f370d9a8b3a2ec2`, jolt-chDB
`3e3141fd29b335aa7bf2b6039fed7b3bbb4ebe04`, and jolt-otel-clickhouse
`dfbc9ea64962b25c2f38912daa1c5a7b7339deaf`. OTel's provider-convergence merge
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
the last successful persistence boundary as `:unavailable`. The separately
versioned `oscope.embedded/status-v2` projects only the public Durable
persistence observation from an owned open connection, and fails closed to
`{:availability :unavailable}` after terminal lifecycle phases, observer
errors, or malformed values. It makes no delivery, timing, object-store, or
remote-reader freshness claim.

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
