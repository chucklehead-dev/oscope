const {test, expect} = require("@playwright/test");
const path = require("node:path");
const {emitCheckout, openCheckoutTrace} = require("./helpers");

const capture = (page, name) => page.screenshot({
  path: path.join("docs", "demo", name),
  fullPage: true,
});

test("capture the checkout investigation and visualization workflow", async ({page, request, baseURL}) => {
  await emitCheckout(request, baseURL);

  const dialog = await openCheckoutTrace(page);
  await expect(dialog).toContainText("checkout workflow completed");
  await dialog.screenshot({path: path.join("docs", "demo", "01-checkout-trace.png")});

  await page.goto("/oscope/events?signal=metrics&metric-kind=gauge&service=oscope-checkout-demo&search=demo.checkout.queue.depth&window=15m&limit=10");
  await expect(page.locator("#oscope-events")).toContainText("demo.checkout.queue.depth");
  await capture(page, "02-queue-depth-metric.png");

  await page.goto("/oscope?signal=metrics&field=metric-name&window=15m&limit=10");
  await expect(page.locator("#oscope-screen svg")).toBeVisible();
  await capture(page, "03-metric-distribution.png");

  await page.getByRole("link", {name: "Edit this chart"}).click();
  await page.getByRole("button", {name: "Load example"}).first().click();
  const editor = page.getByLabel("Chart specification");
  await expect(editor).toHaveValue(/:mark :area/);
  await expect(page.locator("#plotje-preview svg polygon")).toBeVisible();
  await editor.fill((await editor.inputValue()).replace(":mark :area", ":mark :line"));
  await expect(page.locator("#plotje-preview svg polyline")).toBeVisible();
  await capture(page, "04-plotje-line-edit.png");
});
