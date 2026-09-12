# Langfuse span export

Oscope supports two ways to keep local telemetry while sending the same spans
to Langfuse:

- An embedded application can use `oscope.embedded/start!`. Its local pipeline
  writes directly to the shared Durable chDB connection, while its remote
  pipeline sends OTLP/HTTP JSON.
- An application using the standalone Oscope collector can create two
  independently bounded span pipelines in its one OTel SDK: one OTLP exporter
  points at Oscope and the other points at Langfuse. The standalone collector
  does not forward telemetry and does not own the application's SDK.

Both arrangements preserve one trace ID, span ID, and parent relationship at
both destinations. Each destination has its own queue and worker, so remote
latency is not added to application span completion or local queue processing.
Logs and metrics are not sent to Langfuse by this profile.

## Remote configuration

For the embedded API, use the full Langfuse trace endpoint and keep headers in
a secret-injected environment variable:

```clojure
(embedded/start!
 {:db-spec durable-writer-dbspec
  :sdk-options {:service-name "checkout" :metrics? true :logs? true}
  :span-pipelines
  {:local {:max-queue-size 4096 :max-export-batch-size 512
           :schedule-delay-ms 1000}
   :remote
   {:traces-url "https://cloud.langfuse.com/api/public/otel/v1/traces"
    :headers-env "CHECKOUT_LANGFUSE_OTLP_HEADERS"
    :timeout-ms 10000 :max-retries 3
    :max-queue-size 2048 :max-export-batch-size 512
    :schedule-delay-ms 1000}}})
```

`CHECKOUT_LANGFUSE_OTLP_HEADERS` must contain ordinary comma-separated OTLP
headers. The public Langfuse v4 compatibility and OpenTelemetry documentation,
checked on 2026-09-12, specifies this shape:

```text
Authorization=Basic REDACTED,x-langfuse-ingestion-version=4
```

Build the Basic value from the Langfuse public and secret keys in the deployment
secret manager. Do not put it in an EDN file, command line, checked-in script, or
URL. Oscope resolves the variable only inside the private exporter; lifecycle
results contain neither its name nor its value.

See Langfuse's [version compatibility](https://langfuse.com/docs/compatibility)
and [OpenTelemetry integration](https://langfuse.com/integrations/native/opentelemetry)
pages for the current hosted and self-hosted contract. Those public documents
support the profile used here; they are not evidence that this repository's
opt-in gate has passed against a particular project.

This is still normal OpenTelemetry instrumentation. Langfuse-specific span
fields such as `langfuse.observation.type`, input, output, or model name are
producer attributes. Oscope does not rewrite the local copy or pretend to be a
Langfuse dataset, score, or UI implementation.

## Real interoperability gate

The ordinary headless suite opens a real loopback listener and verifies the
exact Langfuse path, ingestion-version header, OTLP JSON shape, and canonical
local/remote span identities. It uses a dummy authorization value.

The opt-in gate goes further. It starts a real standalone Oscope receiver,
creates one application SDK with independent local and remote queues, emits a
two-span nested trace, and requires both of these readbacks:

1. Oscope's chDB rows have the exact trace ID, span IDs, names, and parent.
2. Langfuse's v2 Observations API returns the same two observations with the
   expected names, parent, observation types, input, and output.

Set the following through a secret-aware runner, then execute the script:

```sh
export JOLT_CHDB_LIB=/absolute/path/to/qualified/libchdb.so
export OSCOPE_LANGFUSE_BASE_URL=https://cloud.langfuse.com
export OSCOPE_LANGFUSE_OTLP_HEADERS='Authorization=Basic REDACTED,x-langfuse-ingestion-version=4'
test/langfuse_interop.sh
```

Do not paste real credentials into shell history; the values above are only
placeholders. The same gate is designed for a self-hosted v4 base URL.
Ingestion is asynchronous, so it polls the Observations API for up to 90 seconds
instead of treating an ingestion HTTP 2xx response as proof of semantic storage.

The gate prints only a pass marker and the non-secret trace ID. Failures are
reduced to a fixed message; endpoint URLs, authorization headers, response
bodies, and nested transport exceptions are not printed or retained.
