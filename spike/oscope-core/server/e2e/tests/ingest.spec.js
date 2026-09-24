const { test, expect } = require("@playwright/test");
const { search, total } = require("./helpers");

const now = () => (BigInt(Date.now()) * 1000000n).toString();
const hex = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");

test("OTLP/HTTP JSON logs appear in live search", async ({ page, request }) => {
  const marker = "e2e-live-" + hex(4);
  await search(page, { q: `"${marker}"` });
  expect(await total(page)).toBe(0);
  await page.getByTestId("live").click();
  await expect(page.getByTestId("live")).toHaveAttribute("aria-pressed", "true");
  const res = await request.post("/v1/logs", {
    headers: { "content-type": "application/json" },
    data: { resourceLogs: [{ resource: { attributes: [{ key: "service.name", value: { stringValue: "e2e" } }] },
      scopeLogs: [{ logRecords: [{ timeUnixNano: now(), severityNumber: 17, severityText: "ERROR", body: { stringValue: `${marker} disk full` },
        attributes: [{ key: "disk", value: { stringValue: "/dev/sda1" } }] }] }] }] },
  });
  expect(res.ok()).toBeTruthy();
  // live mode re-queries every 3 s: no reload, no click
  await expect(page.getByTestId("result-row")).toHaveCount(1, { timeout: 8000 });
  await expect(page.getByTestId("result-row")).toContainText("disk full");
  await expect(page.getByTestId("result-row")).toContainText("e2e");
});

test("OTLP/HTTP JSON spans are searchable and form a trace", async ({ page, request }) => {
  const trace = hex(16), root = hex(8), child = hex(8), t = BigInt(Date.now()) * 1000000n;
  const span = (id, parent, name, kind, ms, err) => ({ traceId: trace, spanId: id, parentSpanId: parent, name, kind,
    startTimeUnixNano: String(t), endTimeUnixNano: String(t + BigInt(ms) * 1000000n), status: { code: err ? 2 : 0, message: err || "" },
    attributes: [{ key: "e2e.case", value: { stringValue: "ingest" } }] });
  const res = await request.post("/v1/traces", {
    headers: { "content-type": "application/json" },
    data: { resourceSpans: [{ resource: { attributes: [{ key: "service.name", value: { stringValue: "e2e-svc" } }] },
      scopeSpans: [{ spans: [span(root, "", "GET /e2e", 2, 40), span(child, root, "db.e2e", 3, 25, "boom")] }] }] },
  });
  expect(res.ok()).toBeTruthy();
  await search(page, { source: "traces", q: "service:e2e-svc" });
  expect(await total(page)).toBe(2);
  await page.goto("/#/trace/" + trace);
  await expect(page.getByTestId("span-row")).toHaveCount(2);
  await expect(page.getByTestId("span-row").filter({ hasText: "db.e2e" }).locator(".wf-name .err")).toBeVisible();
});

test("protobuf is refused with 415 until it is supported", async ({ request }) => {
  const res = await request.post("/v1/traces", { headers: { "content-type": "application/x-protobuf" }, data: Buffer.from([0x0a, 0x00]) });
  expect(res.status()).toBe(415);
});
