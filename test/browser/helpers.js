const TRACE_ID = "11111111111111111111111111111111";
const ROOT_SPAN_ID = "2222222222222222";
const SERVICE = "oscope-checkout-demo";

const attribute = (key, stringValue) => ({key, value: {stringValue}});

async function postSignal(request, baseURL, path, data) {
  const response = await request.post(`${baseURL}${path}`, {data});
  if (response.status() !== 200) {
    throw new Error(`${path} fixture ingest returned HTTP ${response.status()}: ${await response.text()}`);
  }
}

async function emitCheckout(request, baseURL) {
  // IDs, names, hierarchy, values, and relative timing are fixed. The base time
  // tracks wall clock solely so the records remain inside oscope's 15m window.
  const now = BigInt(Date.now()) * 1000000n;
  const at = (millisBefore) => String(now - BigInt(millisBefore) * 1000000n);
  const resource = {attributes: [attribute("service.name", SERVICE)]};
  const scope = {name: "oscope.browser.checkout", version: "1.0"};
  const span = (spanId, parentSpanId, name, kind, start, end, extras = {}) => ({
    traceId: TRACE_ID,
    spanId,
    ...(parentSpanId ? {parentSpanId} : {}),
    name,
    kind,
    startTimeUnixNano: at(start),
    endTimeUnixNano: at(end),
    status: {code: 1},
    ...extras,
  });

  await postSignal(request, baseURL, "/v1/traces", {
    resourceSpans: [{resource, scopeSpans: [{scope, spans: [
      span(ROOT_SPAN_ID, null, "POST /checkout", 2, 110, 10, {
        attributes: [attribute("http.request.method", "POST"),
                     attribute("http.route", "/checkout")],
      }),
      span("3333333333333333", ROOT_SPAN_ID, "validate cart", 1, 100, 82, {
        events: [{timeUnixNano: at(88), name: "cart.validated",
                  attributes: [attribute("validation.result", "accepted")]}],
      }),
      span("4444444444444444", ROOT_SPAN_ID, "GET /inventory/{sku}", 3, 80, 40),
      span("5555555555555555", "4444444444444444", "SELECT inventory", 3, 72, 52),
      span("6666666666666666", ROOT_SPAN_ID, "fulfillment enqueue", 4, 38, 16, {
        events: [{timeUnixNano: at(22), name: "job.enqueued",
                  attributes: [attribute("job.id", "demo-job-42")]}],
      }),
    ]}]}],
  });

  await postSignal(request, baseURL, "/v1/logs", {
    resourceLogs: [{resource, scopeLogs: [{scope, logRecords: [
      {timeUnixNano: at(96), observedTimeUnixNano: at(95), severityNumber: 9,
       severityText: "INFO", body: {stringValue: "checkout request accepted"},
       traceId: TRACE_ID, spanId: ROOT_SPAN_ID},
      {timeUnixNano: at(44), observedTimeUnixNano: at(43), severityNumber: 9,
       severityText: "INFO", body: {stringValue: "inventory reservation started"},
       traceId: TRACE_ID, spanId: "4444444444444444"},
      {timeUnixNano: at(12), observedTimeUnixNano: at(11), severityNumber: 9,
       severityText: "INFO", body: {stringValue: "checkout workflow completed"},
       traceId: TRACE_ID, spanId: ROOT_SPAN_ID},
    ]}]}],
  });

  await postSignal(request, baseURL, "/v1/metrics", {
    resourceMetrics: [{resource, scopeMetrics: [{scope, metrics: [
      {name: "demo.checkout.queue.depth", unit: "{job}",
       gauge: {dataPoints: [
         {timeUnixNano: at(660000), asInt: "2"},
         {timeUnixNano: at(360000), asInt: "5"},
         {timeUnixNano: at(60000), asInt: "3"},
         {timeUnixNano: at(8), asInt: "7"},
       ]}},
      {name: "demo.checkout.inflight", unit: "{request}",
       gauge: {dataPoints: [{timeUnixNano: at(7), asInt: "1"}]}},
    ]}]}],
  });

  return TRACE_ID;
}

async function emitGaugeBuckets(request, baseURL, {metricName, buckets}) {
  const bucketMillis = 5 * 60 * 1000;
  const currentBucketMillis = Math.floor(Date.now() / bucketMillis) * bucketMillis;
  const resource = {attributes: [attribute("service.name", SERVICE)]};
  const scope = {name: "oscope.browser.dynamic-chart", version: "1.0"};
  const dataPoints = buckets.flatMap((values, bucketIndex) => {
    // Keep every fixture bucket fully elapsed. Besides avoiding a point just
    // beyond wall clock at a five-minute boundary, this makes both x values
    // stable across the before/after emissions in the acceptance test.
    const bucketStartMillis = currentBucketMillis -
      ((buckets.length - bucketIndex) * bucketMillis);
    return values.map((value, valueIndex) => ({
      timeUnixNano: String(BigInt(bucketStartMillis + 1000 + valueIndex) * 1000000n),
      asDouble: value,
    }));
  });

  await postSignal(request, baseURL, "/v1/metrics", {
    resourceMetrics: [{resource, scopeMetrics: [{scope, metrics: [
      {name: metricName, unit: "{job}", gauge: {dataPoints}},
    ]}]}],
  });
}

async function emitTypedInt64(request, baseURL) {
  const now = BigInt(Date.now()) * 1000000n;
  const at = (millisBefore) => String(now - BigInt(millisBefore) * 1000000n);
  const scope = {name: "oscope.browser.typed-int64", version: "1.0"};
  const resourceSpans = [
    {service: "oscope-typed-alpha", values: [10, 15]},
    {service: "oscope-typed-beta", values: [30]},
  ].map(({service, values}, resourceIndex) => ({
    resource: {attributes: [attribute("service.name", service)]},
    scopeSpans: [{scope, spans: values.map((value, index) => ({
      traceId: `${93 + resourceIndex}${String(index).padStart(30, "0")}`,
      spanId: `${93 + resourceIndex}${String(index).padStart(14, "0")}`,
      name: "typed.score",
      startTimeUnixNano: at(30 - (resourceIndex * 5 + index)),
      endTimeUnixNano: at(29 - (resourceIndex * 5 + index)),
      attributes: [{key: "game.score", value: {intValue: String(value)}}],
    }))}],
  }));
  resourceSpans.push({
    resource: {attributes: [attribute("service.name", "oscope-typed-alpha")]},
    scopeSpans: [{scope, spans: [
      {traceId: "95000000000000000000000000000000",
       spanId: "9500000000000000", name: "typed.invalid",
       startTimeUnixNano: at(10), endTimeUnixNano: at(9),
       attributes: [attribute("game.score", "not-an-int")]},
      {traceId: "96000000000000000000000000000000",
       spanId: "9600000000000000", name: "typed.absent",
       startTimeUnixNano: at(8), endTimeUnixNano: at(7), attributes: []},
    ]}],
  });
  await postSignal(request, baseURL, "/v1/traces", {resourceSpans});
}

async function emitTypedBoolean(request, baseURL) {
  const now = BigInt(Date.now()) * 1000000n;
  const at = (millisBefore) => String(now - BigInt(millisBefore) * 1000000n);
  const scope = {name: "oscope.browser.typed-boolean", version: "1.0"};
  const span = (tracePrefix, name, millisBefore, attributes) => ({
    traceId: `${tracePrefix}${"0".repeat(30)}`,
    spanId: `${tracePrefix}${"0".repeat(14)}`,
    name,
    startTimeUnixNano: at(millisBefore),
    endTimeUnixNano: at(millisBefore - 1),
    attributes,
  });
  const ready = (value) => [{key: "game.ready", value: {boolValue: value}}];
  const resourceSpans = [{
    resource: {attributes: [attribute("service.name", "oscope-typed-boolean")]},
    scopeSpans: [{scope, spans: [
      span("a1", "ready.false.first", 40, ready(false)),
      span("a2", "ready.true", 35, ready(true)),
      span("a3", "ready.false.second", 30, ready(false)),
      span("a4", "ready.invalid", 25,
        [attribute("game.ready", "not-a-boolean")]),
      span("a5", "ready.absent", 20, []),
    ]}],
  }];
  await postSignal(request, baseURL, "/v1/traces", {resourceSpans});
}

async function openCheckoutTrace(page) {
  await page.goto("/oscope/telemetry");
  await page.getByLabel("Service").selectOption(SERVICE);
  await page.getByLabel("Operation or name").fill("POST /checkout");
  await page.getByLabel("Status").selectOption("OK");
  await page.getByRole("button", {name: "Apply filters"}).click();
  const traces = page.locator(".otel-trace-list > li");
  await traces.first().getByRole("link").click();
  return page.getByRole("dialog", {name: "Trace detail"});
}

module.exports = {
  emitCheckout,
  emitGaugeBuckets,
  emitTypedBoolean,
  emitTypedInt64,
  openCheckoutTrace,
  SERVICE,
  TRACE_ID,
};
