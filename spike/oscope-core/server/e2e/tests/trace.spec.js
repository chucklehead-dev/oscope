const { test, expect } = require("@playwright/test");
const { watchErrors, search } = require("./helpers");

test("an error log leads to the full cross-service trace", async ({ page }) => {
  const ok = watchErrors(page);
  await search(page, { q: '"payment gateway timeout"' });
  await page.getByTestId("result-row").first().click();
  await page.getByTestId("open-trace").click();
  await expect(page).toHaveURL(/#\/trace\/[0-9a-f]{32}$/);

  const wf = page.getByTestId("waterfall");
  await expect(wf.getByTestId("span-row")).toHaveCount(8);
  const head = page.getByTestId("trace-head");
  for (const svc of ["frontend", "checkout", "inventory", "payments"]) await expect(head).toContainText(svc);
  // the payment timeout is marked and nested under checkout
  const charge = wf.getByTestId("span-row").filter({ hasText: "POST /charge" });
  await expect(charge.locator(".wf-name .err")).toBeVisible();
  await expect(charge.locator(".wf-bar.err")).toBeVisible();
  const pad = async (name) => parseInt(await wf.getByTestId("span-row").filter({ hasText: name }).locator(".wf-name").evaluate((e) => e.style.paddingLeft));
  expect(await pad("POST /charge")).toBeGreaterThan(await pad("POST /orders"));
  expect(await pad("POST /orders")).toBeGreaterThan(await pad("POST /api/checkout"));

  await expect(page.getByTestId("trace-logs").locator("tbody tr")).toHaveCount(3);
  await charge.click();
  const drawer = page.getByTestId("drawer");
  await expect(drawer).toContainText("gateway timeout after 2000ms");
  await expect(drawer.locator("dt", { hasText: "http.response.status_code" })).toBeVisible();
  await expect(drawer).toContainText("Logs (1)");
  ok();
});

test("an unknown trace id is reported, not a crash", async ({ page }) => {
  await page.goto("/#/trace/" + "0".repeat(32));
  await expect(page.getByText("No spans for this trace")).toBeVisible();
  await page.goto("/#/trace/not-a-trace");
  await expect(page.locator(".error-banner")).toContainText("32 hex");
});
