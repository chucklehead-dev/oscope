# Optional stock-Jolt span encoding

The root Oscope application can opt into a lower-allocation span encoder on
official Jolt 0.8.23 with libchdb 26.9.0. This does not need our compiler fork
or the optional native data.json backend. Logs and metrics still use configured
JSON. All signals retain their existing confirmed Durable writes.

Defaults remain unchanged. This experimental encoder supports its documented
built-in values, not arbitrary JSONWriter extensions. Unsupported span values
fail export rather than disappearing. This is not an upstream-only ecosystem:
the SDK, driver and ordinary JSON pins still include our forks.
The selected component supports the SDK's ordinary record link contexts without
map copies. Older components without that capability reject during startup;
custom record `seq` implementations remain unsupported.

Activate the root `:stock-span-codec` alias when starting Oscope. A library
consumer must instead declare the independent component in its own deps.edn;
dependency aliases do not propagate:

```clojure
io.github.chucklehead-dev/jolt-codec
{:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git"
 :git/sha "7ab2bedcb2e1845efdf6ed5c55ae9d2164338a8d"
 :deps/root "incubator/codec"}
```

For standalone Durable storage from the root checkout, select the published
dependency stack without the sibling-checkout overrides in `:durable-server-dev`:

```sh
jolt -Srepro -A:stock-span-codec -m oscope.durable-server-main --config /path/to/oscope.edn
```

Set `JOLT_CHDB_LIB` to the qualified native library if it is not installed at
the driver's expected path. Local workspace commands require the Chez wrapper.
The ordinary `:server` launcher is not the Durable writer owner.

In a version 2 config, for example:

```clojure
{:version 2
 :storage
 {:type :durable-local :root "/path/to/telemetry"
  :owned-compact-stream? true
  :export-options
  {:insert-format :json-compact-each-row
   :json-backend :native-guarded-byte-batch
   :owned-statement-output? true
   :datetime64-wire :raw-ticks
   :experimental-span-codec? true
   :experimental-span-payload-cache? true
   :experimental-span-flat-map-cache? true
   :experimental-span-nested-fragment-cache? true}}}
```

The three cache flags are independent, optional and default false. Caches are
bounded per synchronous span payload, not shared telemetry stores. They do not
redact attributes or change persistence acknowledgement. `:owned-compact-stream?`
belongs to the writer storage configuration, not `:export-options`.

`oscope.embedded/start!` accepts the same `:export-options` map alongside the
caller's Durable writer db-spec. Put `:owned-compact-stream? true` in that
writer spec to select native streaming. Embedded logs still need `:logs? true`
in SDK options; metrics default on. Standalone supports all three signals.

Invalid combinations reject before application acquisition. Explicit codec
requests also probe library/SDK/owned-statement capabilities before opening
JDBC or remote exporters. Missing support rejects; it does not silently fall
back. The probe does not establish schema, native-version or delivery readiness;
construction still checks those independently.

The separately pinned `profiles/embedded` dependency profile is not yet
upgraded or qualified for this opt-in. Its older exporter lacks the public
probe, so requests reject before storage. Use the root stack for this experiment.

The maintained stock CI fixture requires actual codec and native-stream calls,
forbids the legacy native JSON writer, and checks independent recovery after
SDK or socket capture. It does not establish S3, Langfuse, application standalone
packaging, sustained throughput or population-tail performance. No throughput
target is claimed by enabling these options.
