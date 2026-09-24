const { test, expect } = require("@playwright/test");
const { watchErrors } = require("./helpers");

test("services show RED metrics, sparklines and endpoints", async ({ page }) => {
  const ok = watchErrors(page);
  await page.goto("/#/services?range=1h");
  const rows = page.getByTestId("service-row");
  // the seeded services; other tests may ingest more
  for (const svc of ["frontend", "checkout", "inventory", "payments"]) await expect(page.locator(`[data-service="${svc}"]`)).toBeVisible();
  await expect(rows.first()).toContainText("frontend"); // most requests first
  const checkout = rows.filter({ hasText: "checkout" });
  await expect(checkout.locator(".pill")).toHaveText(/\d+\.\d\d%/);
  await expect(checkout.locator("svg.spark")).toHaveCount(2);
  await checkout.click();
  await expect(page).toHaveURL(/open=checkout/);
  await expect(page.locator("tr.endpoints", { hasText: "POST /orders" })).toBeVisible();
  await checkout.getByRole("link", { name: "spans →" }).click();
  await expect(page).toHaveURL(/source=traces/);
  await expect(page.getByTestId("query")).toHaveValue("service:checkout kind:server");
  await expect(page.getByTestId("result-row").first()).toContainText("checkout");
  ok();
});
