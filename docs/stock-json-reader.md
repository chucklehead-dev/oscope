# Optional stock-Jolt JSON reader

The HTTP receiver can use our explicit JSON reader for faster document parsing.
This is independent of the span encoder: you can enable either or both. The
default remains the configured data.json reader. No JSON parser is globally
replaced, and direct in-process SDK capture already skips HTTP/JSON decoding.

Use a version-2 file:

```clojure
{:version 2
 :ingest {:type :otlp-http-json :json-reader :experimental-stock}
 :storage {:type :durable-local :root "/path/to/telemetry"}}
```

From this checkout, activate the component when starting the CLI server:

```sh
jolt -Srepro -A:stock-json-reader -m oscope.durable-server-main --config /path/to/oscope.edn
```

Combine `:stock-span-codec:stock-json-reader` if you also select the experimental
span encoder in [export options](stock-codec.md). Both aliases pin the same
component. For memory/local-path storage use `oscope.server-main` instead.
Code embedding the HTTP server can pass `:json-reader :experimental-stock`
to `oscope.server/start!`. Pure SDK embedding has no JSON-ingress setting.

Dependency aliases are not inherited by library consumers. Such applications
must declare the component explicitly:

```clojure
io.github.chucklehead-dev/jolt-codec
{:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
 :git/sha "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d"
 :deps/root "incubator/codec"}
```

The reader is qualified on official stock Jolt 0.8.23. No compiler patch or
data.json backend-hook fork is required by the reader component. Oscope's
existing configured numeric conversion/fallback remains the adapter; this does
not remove all other application fork dependencies.

Missing/incompatible optional capabilities fail before JDBC/DDL acquisition.
Runtime config materialization can already have constructed a storage backend;
this is not a promise of no earlier filesystem effects. `--check-config` stays
pure and checks configuration, not optional runtime availability. Omission or
`:json-reader :configured` selects the old behavior. Version 1 cannot opt in.

Input-size/admission limits, typed ingestion and confirmed Durable ACK behavior
do not change. Numeric types come from the configured parser. Duplicate keys,
unsupported/malformed/deep inputs and raw-control policy defer to fallback.
Values are not sanitized/redacted; valid escaped controls still decode. No
telemetry is logged for capability diagnostics.

The published-library benchmark showed a first 1.66x local socket-throughput
gain with lower estimated allocation and independent recovery. That used
benchmark routing, not this production config path; a real config-selected
socket/recovery fixture remains a separate gate. No sustained/population-tail,
mixed/typed/concurrent traffic, whole-app native binary or S3 performance claim
follows from the library checks. Do not use the old hook's 2.05x figure as this
API's result. CLI server startup is distinct from a bundled application build.
