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
       gauge: {dataPoints: [{timeUnixNano: at(8), asInt: "2"}]}},
      {name: "demo.checkout.inflight", unit: "{request}",
       gauge: {dataPoints: [{timeUnixNano: at(7), asInt: "1"}]}},
    ]}]}],
  });

  return TRACE_ID;
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

module.exports = {emitCheckout, openCheckoutTrace, SERVICE, TRACE_ID};
