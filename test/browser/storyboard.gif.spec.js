const {test, expect} = require("@playwright/test");
const path = require("node:path");
const {emitCheckout, openCheckoutTrace} = require("./helpers");

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

test("record the checkout-to-chart tour", async ({page, request, baseURL}) => {
  await emitCheckout(request, baseURL);
  const dialog = await openCheckoutTrace(page);
  await expect(dialog).toContainText("checkout workflow completed");
  await pause(1400);

  await page.keyboard.press("Escape");
  await page.getByRole("link", {name: "Logs & metrics"}).click();
  await page.getByLabel("Signal").selectOption("metrics");
  await page.getByRole("button", {name: "Run query"}).click();
  await page.getByLabel("Metric kind").selectOption("gauge");
  await page.getByLabel("Metric name contains").fill("demo.checkout.queue.depth");
  await page.getByRole("button", {name: "Run query"}).click();
  await expect(page.locator("#oscope-events")).toContainText("demo.checkout.queue.depth");
  await pause(1400);

  await page.getByRole("link", {name: "Charts & distributions"}).click();
  const query = page.getByRole("form", {name: "Telemetry query"});
  await query.getByLabel("Signal").selectOption("metrics");
  await query.getByRole("button", {name: "Run query"}).click();
  await query.getByLabel("Group by").selectOption("metric-name");
  await query.getByRole("button", {name: "Run query"}).click();
  await expect(page.locator("#oscope-screen svg")).toBeVisible();
  await pause(1400);

  await page.getByRole("link", {name: "Edit this chart"}).click();
  await page.getByRole("button", {name: "Load example"}).first().click();
  const editor = page.getByLabel("Chart specification");
  await expect(page.locator("#plotje-preview svg polygon")).toBeVisible();
  await pause(1400);
  await editor.fill((await editor.inputValue()).replace(":mark :area", ":mark :line"));
  await expect(page.locator("#plotje-preview svg polyline")).toBeVisible();
  await pause(1800);

  const video = page.video();
  await page.close();
  await video.saveAs(path.join("test-results", "oscope-checkout-tour.webm"));
});
