# Configuration

Oscope's standalone server accepts one versioned EDN configuration document.
The same options apply to the `-M:native-server` launcher.
Configuration precedence is, from strongest to weakest:

```text
command line > environment > explicit file > defaults
```

Pass a file with `--config PATH`, or set `OSCOPE_CONFIG`. Oscope does not load a
file from the working directory implicitly. `--check-config` validates the
combined configuration, prints deterministic redacted diagnostic EDN, and
exits before opening a database or listener. The diagnostic output is
parseable for tooling, but conspicuous `<redacted>` placeholders deliberately
make it unsuitable as a reusable configuration file.

```sh
jolt -M:server --config config/oscope.example.edn --check-config
jolt -M:server --config /etc/oscope.edn --port 14318
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

The ordinary `-M:server` launcher currently starts only `:memory` and
`:local-path` storage. Version 2 validates Durable variants so they can be
managed safely, but wiring those documents into `-M:durable-server-dev` remains
a follow-up slice. Existing Durable environment variables remain unchanged.

Diagnostic rendering redacts local paths, object-store locations, Durable
owner/instance identities, and database names. Field provenance is retained
internally as `:default`, `:file`, `:environment`, or `:cli` for a future
settings/status screen.

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
versioned Durable launcher adapter remains separate work: `:durable-local` and
`:durable-s3` config documents are validated, but the ordinary `-M:server`
launcher does not own their writer lifecycle or backend and will not invent a
second one.
