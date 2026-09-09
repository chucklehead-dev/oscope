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

## Version 1 document

[`config/oscope.example.edn`](../config/oscope.example.edn) is the canonical
local-path example. The schema is closed: unknown versions, sections, fields,
ingest types, and storage variants fail before startup.

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
`:local-path` storage. Version 1 validates Durable variants so they can be
managed safely, but wiring those documents into `-M:durable-server-dev` remains
a follow-up slice. Existing Durable environment variables remain unchanged.

Diagnostic rendering redacts local paths, object-store locations, Durable
owner/instance identities, and database names. Field provenance is retained
internally as `:default`, `:file`, `:environment`, or `:cli` for a future
settings/status screen.
