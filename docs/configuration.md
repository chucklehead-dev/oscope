# Configuration

Oscope's standalone server accepts one versioned EDN configuration document.
The same options apply to the `-M:native-server` launcher.

### Export encoding

Version 2 storage sections may include `:export-options`. For example, ordinary
memory storage can use compact JSON rows without changing persistence behavior:

```clojure
{:version 2
 :storage {:type :memory
           :export-options {:insert-format :json-compact-each-row
                            :json-backend :configured}}}
```

The same map is accepted as `:export-options` by `oscope.embedded/start!` and
`oscope.server/start!`. Omitting it preserves all exporter defaults. It accepts
only these encoding settings, not connections, signals, schema descriptors, or
persistence callbacks:

- `:insert-format`: `:json-each-row` or `:json-compact-each-row`.
- `:json-backend`: `:configured`, `:native-guarded`,
  `:native-guarded-string-cache`, or `:native-guarded-byte-batch`.
- `:owned-statement-output?`: a Boolean, defaulting to false.
- `:max-insert-bytes`: a positive integer through 67,108,864 (64 MiB).
  Omit it or use `nil` to retain 8 MiB. For example, `:max-insert-bytes 33554432`
  allows up to 32 MiB per encoded physical insert payload, including row
  newlines but excluding the SQL prefix. It is not a queue or heap budget;
  larger limits use more memory. Driver/Durable whole-statement limits still
  apply, and multi-insert metric exports do not become atomic. Overflow keeps
  the exporter's existing failed-export/diagnostic behavior. Persistence
  acknowledgements and all other encoding/storage defaults are unchanged.
- `:datetime64-wire`: `:auto`, `:iso-utc`, or `:raw-ticks`. The default `:auto`
  uses integer nanoseconds on native 26.7.3 and UTC ISO strings on 26.9.0.
  Explicit `:iso-utc` requires 26.9.0. `:raw-ticks` also requires Durable storage;
  the pinned exporter records its existing query-local setting in the exact
  replayable SQL statement, not a new WAL metadata field.

Owned output is opt-in and requires Durable storage, compact rows, and
`:native-guarded-byte-batch`. Native byte and owned modes additionally require
the qualified byte-capable Jolt runtime and the `:native-json-byte-batch` alias
(which selects the matching JSON library). They are not supported by stock Jolt
merely by setting this map. For example, add the alias to the qualified runtime's
normal server command: `-M:native-json-byte-batch:server --config PATH`.
Other library aliases do not propagate automatically into Oscope.

Configuration validation checks the option names and combinations without
opening storage or probing native encoder availability. An unavailable native
encoder is rejected during actual startup, with normal startup cleanup; no
automatic downgrade or retry with a different encoder occurs. Version 1 files
do not accept this extension. Encoding options do not change acknowledgement,
flush, recovery, or shutdown contracts.

### Optional native 26.9 streaming

For a **new** Durable object, version-2 `:durable-local` and `:durable-s3`
storage can set `:owned-compact-stream? true`. This is a writer option, not an
export encoding option. Only eligible owned compact statements use streaming;
other statements keep the ordinary execution path. For example:

```clojure
{:version 2
 :storage {:type :durable-local
           :root "./oscope-269-fresh"
           :owned-compact-stream? true
           :export-options {:insert-format :json-compact-each-row
                            :json-backend :native-guarded-byte-batch
                            :owned-statement-output? true
                            :datetime64-wire :raw-ticks}}}
```

Select the checksum-qualified 26.9.0 library with `JOLT_CHDB_LIB` and use the
qualified byte-capable runtime plus `:native-json-byte-batch` alias described
above. The installer still defaults to 26.7.3; neither this document nor
check-only configuration switches native libraries. Pure option validation
does not probe the native ABI. The pinned exporter/driver enforce native-version
and runtime requirements during startup; failure follows Oscope's normal cleanup,
not a silent downgrade.

Do not point 26.9 at existing 26.7 Durable telemetry objects: replay of their
older integer-timestamp WAL has not been qualified. These options select existing
[driver streaming behavior](https://github.com/chucklehead-dev/jolt-chdb/blob/3d766fa295b0e98c30f275d0a334db92e6dd998c/docs/durable.md):
the Durable V1 container and the driver's confirmation/fencing rules stay in
place. Oscope does not implement or override those persistence rules.
For embedded/programmatic startup, put `:owned-compact-stream?` in the options
passed to `jdbc.chdb.durable/writer-dbspec`, and pass `:datetime64-wire` in
`:export-options`. Readers must not receive the writer-only streaming option.

Maintainers can exercise real ingestion with the existing recovery fixtures:

```sh
# Set JOLT_CHDB_LIB to the qualified native library and JOLT_BIN to the selected
# runtime (absolute paths). Use the workspace Chez wrapper for local commands.
"$JOLT_BIN" -Srepro -A:test-durable -m oscope.export-options-native-test-runner configured standalone
"$JOLT_BIN" -Srepro -A:test-durable -m oscope.export-options-native-test-runner configured embedded
# With the qualified byte-capable runtime:
"$JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner owned standalone
"$JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner owned embedded
"$JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner owned embedded-typed
# Fresh 26.9 object only, with the qualified writer and a separate stock reader:
# invoke the writer below; JOLT_BIN selects the nested recovery reader.
"$WRITER_JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner stream embedded
"$WRITER_JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner stream-budget embedded-typed
"$WRITER_JOLT_BIN" -Srepro -A:test-durable:native-json-byte-batch -m oscope.export-options-native-test-runner stream-budget standalone
```

Each invocation owns one native writer lifetime. The standalone and embedded
capture fixtures require a checked, settled fresh-reader receipt; the typed
fixture checks approved-manifest ingestion and schema-aware live queries.
These are correctness checks, not throughput benchmarks.

### Configuration precedence

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

### Experimental S3 upload confirmation

For a provider qualified for chDB's single-PUT SHA256 contract, you can add
`:wal-upload-confirmation :sha256-single-put` inside `:storage :s3`. In the
Durable launcher environment, use
`OSCOPE_DURABLE_S3_WAL_UPLOAD_CONFIRMATION=sha256-single-put` alongside your
complete S3 environment selection. Embedded startup accepts the same config
field. Omit the setting to retain normal readback confirmation; unknown modes
are rejected before backend creation.

This may avoid a redundant WAL download after a qualified new upload. It does
not disable independent reader verification or alter captured telemetry, lease
ownership, head CAS or acknowledgement guarantees. Missing evidence, unsupported
transports and ambiguous/existing uploads retain the library's readback path.
The requested setting is not proof that checksum confirmation was selected.
Do not enable it automatically for arbitrary S3-compatible services. Performance
and provider qualification remain separate from accepting the configuration.

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
