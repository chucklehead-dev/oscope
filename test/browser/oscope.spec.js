const {test, expect} = require("@playwright/test");
const {emitCheckout, openCheckoutTrace} = require("./helpers");

test("investigates checkout telemetry and changes its visual grammar", async ({page, request, baseURL}) => {
  await emitCheckout(request, baseURL);
  const dialog = await openCheckoutTrace(page);

  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("POST /checkout");
  await expect(dialog.locator(".otel-spans > li")).toHaveCount(5);
  await expect(dialog).toContainText("cart.validated");
  await expect(dialog).toContainText("job.enqueued");
  await expect(dialog).toContainText("checkout workflow completed");
  await page.keyboard.press("Escape");

  await page.goto("/oscope/events");
  await page.getByLabel("Signal").selectOption("metrics");
  await page.getByRole("button", {name: "Run query"}).click();
  await page.getByLabel("Metric kind").selectOption("gauge");
  await page.getByLabel("Metric name contains").fill("demo.checkout.queue.depth");
  await page.getByRole("button", {name: "Run query"}).click();
  await expect(page.locator("#oscope-events")).toContainText("demo.checkout.queue.depth");
  await expect(page.locator("#oscope-events tbody tr")).toHaveCount(4);

  await page.getByRole("link", {name: "Charts & distributions"}).click();
  const query = page.getByRole("form", {name: "Telemetry query"});
  await query.getByLabel("Signal").selectOption("metrics");
  await query.getByRole("button", {name: "Run query"}).click();
  await query.getByLabel("Group by").selectOption("metric-name");
  await query.getByRole("button", {name: "Run query"}).click();
  await expect(page.locator("#oscope-screen svg")).toBeVisible();
  await expect(page.locator("#oscope-screen")).toContainText("demo.checkout.queue.depth");

  await query.locator('select[name="mode"]').selectOption("metric-series");
  await query.getByRole("button", {name: "Run query"}).click();
  const series = page.getByRole("form", {name: "Metric series query"});
  await series.getByLabel("Exact metric name").fill("demo.checkout.queue.depth");
  await series.getByLabel("Time bucket").selectOption("5m");
  await series.getByRole("button", {name: "Run query"}).click();
  await expect(page.locator("#oscope-screen")).toContainText("demo.checkout.queue.depth · avg, p95");
  await expect(page.locator("#oscope-screen")).toContainText("Bucket Start Unix Nano");
  await expect(page.locator("#oscope-screen svg polyline")).toHaveCount(1);

  await page.getByRole("link", {name: "Edit this chart"}).click();
  const editor = page.getByLabel("Chart specification");
  await expect(editor).toHaveValue(/:mark :line/);
  await expect(editor).toHaveValue(/:data \{:source :current-query, :select \[:bucket-start-unix-nano :service-name :avg :p95\]\}/);
  await expect(editor).toHaveValue(/demo\.checkout\.queue\.depth/);
  await expect(editor).not.toHaveValue(/:p95 7\.0/);
  await page.getByRole("button", {name: "Load example"}).first().click();
  await expect(editor).toHaveValue(/:mark :area/);
  await expect(page.locator("#plotje-preview svg polygon")).toBeVisible();

  await editor.fill((await editor.inputValue()).replace(":mark :area", ":mark :line"));
  await expect(page.locator("#plotje-preview svg polyline")).toBeVisible();
  await expect(page.locator("#plotje-preview")).toContainText("Latency band");
});
