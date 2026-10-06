# Configuration

Oscope's standalone server accepts one versioned EDN configuration document.

Version 2 optionally accepts `:json-backend :native-guarded` inside `:ingest`
to select the general exporter's guarded native JSON fallback. Omitting it
keeps `:configured`; specialized span/log encoders are unaffected. Native mode
requires the qualified compiler-bearing source runtime and fails before
database acquisition when unavailable. It does not change the 8 MiB payload
bound or Durable acknowledgements. Standalone/AOT support is not qualified.
Embedded callers select the same option directly in `oscope.embedded/start!`.
Version 1 cannot select this new backend option.

On the qualified source runtime, the experimental `:native-string-cache` alias
selects matching exact chDB, exporter and data.json candidate pins without
changing the default dependencies. Version 2 can then select:

```clojure
:ingest {:type :otlp-http-json
         :json-backend :native-guarded-string-cache
         :insert-format :json-compact-each-row}
```

These are separate choices. Compact input sends schema-ordered arrays rather
than repeating column names; the exporter confirms the live schema at startup
and fails if it cannot confirm the plan. The string cache retains at most 128
stock-string fragments and 65,536 input-plus-output characters in each payload
or worker, then discards them. Custom writers still run normally. Neither
changes captured values, the serial UTF-8 limit or Durable acknowledgement.
Omitting both preserves existing named input and configured encoding.

Embedded callers pass `:json-backend` and `:insert-format` directly to `start!`.

Both root and embedded development profiles offer the alias; dependency aliases
do not propagate to applications, so consumers must explicitly select matching
pins. Encoder choices remain opt-in; no standalone/AOT qualification is implied.
Version 1 cannot select either new encoder option.

Durable storage can optionally set `:checkpoint-wal-reference-threshold` to a
positive integer, for example `128`. The writer publishes a full checkpoint
when another WAL publication would reach that limit. This counts physical WAL
references, not rows or logical OTLP requests; it is separate from Oscope's
`:checkpoint-every-batches` cadence. Omitting it preserves the existing policy.
A smaller reference list can reduce head-processing and recovery work, but
full backups may increase tail latency. It is not a throughput/latency guarantee.
The default and optional chDB pins include the JDBC forwarding fix. Embedded
callers set the same option on their Durable `writer-dbspec`.

Run cached source gates in separate processes from the repository root:

```sh
jolt -Sdeps '{:paths ["src" "test"]}' -M:native-string-cache -m oscope.json-backend-test cached-controls
jolt -Sdeps '{:paths ["src" "test"]}' -M:native-string-cache -m oscope.json-backend-test cached-embedded
jolt -Sdeps '{:paths ["src" "test"]}' -M:native-string-cache -m oscope.json-backend-test cached-metrics
jolt -Sdeps '{:paths ["src" "test"]}' -M:native-string-cache -m oscope.json-backend-test cached-standalone
```

Local workspace gates must also use the mandated Chez 10.4.1 command wrapper.
For suites that launch child processes, set `JOLT_BIN` to that same qualified
wrapper/executable; a newer parent command does not upgrade `jolt` on PATH.
The historical `cached-metrics` slice also captures span events and correlated
logs with structured and empty bodies through the real installed SDK.
These tests cover startup, typed collection/readback and restart, not throughput,
S3 or native bundled builds. Published exporter mean screens around 31k rows/s
do not establish Oscope throughput or the requested span-tail targets.

On that qualified runtime, run `jolt -M:test-native-json`,
`jolt -M:test-native-json-standalone` and `jolt -M:test-native-json-embedded`
as separate commands. The socket and Durable SDK fixtures must own separate
native process lifetimes; closing one is not a reset of chDB's process anchor.
The same options apply to the `-M:native-server` launcher.
Configuration precedence is, from strongest to weakest:

```text
command line > environment > selected file > defaults
```

Pass a file with `--config PATH`, or set `OSCOPE_CONFIG`. When neither is set,
Oscope uses a platform user-config file only if that exact file already exists:

- `$XDG_CONFIG_HOME/oscope/config.edn`, or
  `$HOME/.config/oscope/config.edn`, on Linux and other Unix-like systems;
- `$XDG_CONFIG_HOME/oscope/config.edn` when XDG is explicitly configured on
  macOS, otherwise `$HOME/Library/Application Support/oscope/config.edn`;
- `%APPDATA%\oscope\config.edn` on Windows, with the usual
  `%USERPROFILE%\AppData\Roaming`-equivalent user-home fallback.

Relative XDG, home, and application-data roots are ignored, and Oscope never
looks for a configuration file in the working directory. An explicit
`--config` still wins over `OSCOPE_CONFIG`, and both explicit selectors are
read-or-error contracts rather than optional discovery.

The configuration selector also retains a closed internal origin:
`command-line`, `environment`, `managed-user`, or `none`. The selected path is
not part of that public metadata. Managed writes are eligible only for the
canonical platform user path selected as `managed-user`, or for that same path
before it exists (`none`). Files explicitly selected by `--config` or
`OSCOPE_CONFIG` are always read-only.

The managed-store API uses an opaque revision of the exact deterministic file
bytes for compare-and-swap updates. Callers must read a snapshot, submit that
revision with a complete validated document, and resnapshot after any failure.
The storage adapter must serialize competing processes, reject links and
unowned or incorrectly permissioned files, durably verify a private temporary
file, atomically replace it in the same directory, and sync the directory.
Platforms without a qualified implementation fail closed. This API is a
storage foundation for future settings and first-run screens; it does not yet
enable either UI.

The current native adapter is qualified only for Linux x86-64. It holds a
private descriptor-relative lock across reread, revision comparison, temporary
write, and replacement; checks the effective owner and exact `0700` directory
and `0600` file modes; syncs the temporary file; reads it back; uses native
same-directory rename without pre-deleting the destination; then syncs the
directory. macOS remains disabled until exercised against its ABI and
durability behavior. Windows remains disabled until an equivalent ACL and
atomic-replacement contract is qualified.

`--check-config` applies the same selection and precedence, validates the
combined configuration, prints deterministic redacted diagnostic EDN, and
exits before opening a database or listener. Neither the selected path nor
sensitive configuration values enter that output. The diagnostic output is
parseable for tooling, but conspicuous `<redacted>` placeholders deliberately
make it unsuitable as a reusable configuration file.

```sh
jolt -M:server --config config/oscope.example.edn --check-config
jolt -M:server --config /etc/oscope.edn --port 14318
jolt -M:durable-server-dev --config config/oscope-durable.example.edn --check-config
jolt -M:durable-server-dev --config /etc/oscope-durable.edn
```

The initial command-line overrides are `--host`, `--port`, and `--db-spec`.
Existing `OSCOPE_HOST`, `OSCOPE_PORT`, and `OSCOPE_CHDB_SPEC` deployments keep
the same behavior. The bounded dispatcher settings `OSCOPE_HTTP_WORKERS` and
`OSCOPE_HTTP_QUEUE_CAPACITY` also participate in the same model and may be set
as `:server` fields in the file. Only the numeric loopback host `127.0.0.1` is
accepted.

## Version 2 document

[`config/oscope.example.edn`](../config/oscope.example.edn) is the canonical
local-path example. The schema is closed: unknown versions, sections, fields,
ingest types, storage variants, and typed-attribute modes fail before startup.
Existing version 1 files are accepted and normalized to version 2 with
`{:typed-attributes {:mode :disabled}}`; version 1 cannot opt into typed
attributes.

Storage variants are:

- `{:type :memory}`
- `{:type :local-path :path "/var/lib/oscope"}`
- `{:type :durable-local :root "/var/lib/oscope-durable" ...}`
- `{:type :durable-s3 ...}`

Durable S3 files may contain only credential references, never credentials:

```clojure
{:type :durable-s3
 :s3 {:endpoint "https://s3.example.com"
      :bucket "telemetry"
      :region "us-west-2"
      :object-id "oscope"
      :credentials {:type :environment
                    :access-key-env "AWS_ACCESS_KEY_ID"
                    :secret-key-env "AWS_SECRET_ACCESS_KEY"
                    :session-token-env "AWS_SESSION_TOKEN"}}}
```

The ordinary `-M:server` launcher starts only `:memory` and `:local-path`
storage. The ownership-specific `-M:durable-server-dev` launcher accepts only
`:durable-local` and `:durable-s3`, using the same file selection, command-line
server overrides, validation, and redacted `--check-config` path. Existing
Durable environment variables remain supported and replace the complete file
`:storage` section, so fields from two storage variants cannot mix.

Credential reference names are validated with the file. Their values are read
only when the Durable S3 runtime is actually materialized, after check-only
handling, and are passed directly to the backend. A missing required value
fails before backend creation. Neither config diagnostics nor bounded startup
errors contain credential values.

Diagnostic rendering redacts local paths, object-store locations, Durable
owner/instance identities, database names, and credential-reference names.
Field provenance is retained internally as `:default`, `:file`, `:environment`,
or `:cli` for a future settings/status screen.

## Approved typed attributes

Version 2 adds one optional, whole-replacement `:typed-attributes` section. It
is not merged field by field across configuration layers. The default is:

```clojure
{:typed-attributes {:mode :disabled}}
```

Install mode names an already compiled and reviewed exporter manifest by an
absolute path and the SHA-256 of its exact bytes. It also names the closed
deployment identity the manifest is allowed to install:

```clojure
{:typed-attributes
 {:mode :install
  :manifest {:path "/etc/oscope/typed-attributes.edn"
             :sha256 "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
  :registry {:dataset-id "telemetry-prod"
             :application-id "checkout"
             :lineage "checkout-v1"
             :version 1}}}
```

Acquire mode selects the same identity but does not authorize manifest loading,
catalog writes, or DDL:

```clojure
{:typed-attributes
 {:mode :acquire
  :registry {:dataset-id "telemetry-prod"
             :application-id "checkout"
             :lineage "checkout-v1"
             :version 1}}}
```

There are no manifest command-line flags or environment aliases. Put this
section in the file selected by the existing `--config` or `OSCOPE_CONFIG`
mechanism.

`--check-config` accepts manifest files of at most 8 MiB, compares the digest of
the exact file bytes before parsing EDN, validates the compiled manifest through
the exporter, and requires its deployment identity to equal `:registry`. It
opens neither chDB storage nor a listener. Diagnostic output redacts the
manifest path and all selector strings, and it discards the loaded manifest
capability after the check.

For ordinary `:local-path` storage, Oscope derives one persistent registry
namespace beside the canonical chDB path and uses one fixed private object scope
within it. Install and acquire therefore reopen the same cross-process CAS
catalog after restart without exposing a declared dataset name in a filesystem
component. All dataset declarations targeting the same physical database share
that catalog, preserving the exporter's cross-dataset physical-column collision
checks. The backend is passed through the existing typed-schema startup
boundary; the exporter remains the sole schema owner.

Once startup confirms an Int64 span field, the web viewer offers both exact
typed filters and a numeric summary. The summary accepts an inclusive lower
bound and optional exclusive upper bound, can group by service name, and shows
`count`, `min`, `max`, and `avg`. Saved URLs serialize the exact four-part
logical binding (field ID, attribute key, type, and manifest version), so a
catalog change produces a visible stale-binding response instead of silently
following a different column. Interactive field-only forms redirect to that
canonical URL before running the query. A serialized binding is either absent
or complete, and its manifest version must be in the positive signed-Int64
domain.

Approved Boolean fields use an explicit true/false selector. A false value is
kept as typed data through ingestion, filtering, saved URLs, and display rather
than being treated as absent. Their coverage table shows the same six
availability and historical states plus the conserved total.

The aggregate table counts only values confirmed as valid Int64 data. A
separate coverage table shows valid, present-empty, absent, invalid,
historical-with-fallback, and historical-unavailable rows plus their conserved
total. Historical fallback text remains visible as coverage but is never parsed
for a numeric result. Coverage and aggregates are separate bounded live queries,
not a snapshot; Oscope displays that freshness boundary when ingestion can
advance between them. All physical SQL, tables, columns, limits, and resource
settings remain owned by `jolt-otel-clickhouse`.

`:memory` is rejected for non-disabled typed attributes because a process-only
registry would make restart and read-only acquisition claims false. The
ownership-specific Durable launcher gives typed install/acquire modes a
registry object distinct from the telemetry object while retaining the same
Durable namespace. The ordinary `-M:server` launcher still does not own a
Durable writer lifecycle or backend and will not invent a second one.
