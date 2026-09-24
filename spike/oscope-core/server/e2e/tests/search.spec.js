const { test, expect } = require("@playwright/test");
const { watchErrors, search, total } = require("./helpers");

test.describe("log search", () => {
  test("shows volume histogram, facets and newest events", async ({ page }) => {
    const ok = watchErrors(page);
    await search(page);
    expect(await total(page)).toBeGreaterThan(10000);
    // ~60 buckets: an unaligned window spills into one more
    const buckets = await page.getByTestId("histogram").locator("svg .bucket").count();
    expect(buckets).toBeGreaterThanOrEqual(60);
    expect(buckets).toBeLessThanOrEqual(61);
    const facets = page.getByTestId("facets");
    for (const f of ["service", "level", "http.response.status_code"]) await expect(facets.locator("h4", { hasText: f })).toBeVisible();
    await expect(page.getByTestId("result-row")).toHaveCount(200);
    ok();
  });

  test("query language filters by level and service", async ({ page }) => {
    const ok = watchErrors(page);
    await search(page);
    const all = await total(page);
    await page.getByTestId("query").fill("level:error service:payments");
    await page.getByTestId("run").click();
    await expect(page).toHaveURL(/q=level%3Aerror/);
    const filtered = await total(page);
    expect(filtered).toBeGreaterThan(0);
    expect(filtered).toBeLessThan(all);
    const rows = page.getByTestId("result-row");
    for (const row of await rows.all()) {
      await expect(row).toContainText("payments");
      await expect(row).toContainText("ERROR");
    }
    ok();
  });

  test("free text matches message bodies", async ({ page }) => {
    await search(page, { q: '"gateway timeout"' });
    expect(await total(page)).toBeGreaterThan(0);
    for (const row of await page.getByTestId("result-row").all()) await expect(row).toContainText(/gateway timeout/i);
  });

  test("clicking a facet adds a filter; shift-click excludes", async ({ page }) => {
    await search(page);
    await page.getByTestId("facets").locator('[data-term="service:inventory"]').click();
    await expect(page).toHaveURL(/q=service%3Ainventory/);
    for (const row of await page.getByTestId("result-row").all()) await expect(row).toContainText("inventory");
    await expect(page.getByTestId("facets").locator('[data-term="service:inventory"]')).toHaveClass(/on/);

    await search(page);
    await page.getByTestId("facets").locator('[data-term="level:INFO"]').click({ modifiers: ["Shift"] });
    await expect(page).toHaveURL(/q=-level%3AINFO/);
    await expect(page.getByTestId("result-row").locator(".lv-INFO")).toHaveCount(0);
  });

  test("an invalid query explains what is wrong", async ({ page }) => {
    await page.goto("/#/search?source=logs&range=1h&q=" + encodeURIComponent("bad!key:1"));
    await expect(page.getByTestId("query-error")).toContainText("invalid field name");
  });

  test("injection attempts stay inside a string literal", async ({ page }) => {
    await search(page, { q: "body:x');DROP TABLE otel_logs;--" });
    expect(await total(page)).toBe(0);
    await search(page);
    expect(await total(page)).toBeGreaterThan(10000);
  });

  test("clicking a histogram bar zooms into that interval", async ({ page }) => {
    await search(page);
    const before = await total(page);
    await page.getByTestId("histogram").locator("svg .bucket").nth(30).click();
    await expect(page).toHaveURL(/from=\d+&to=\d+/);
    await expect(page.getByText("reset zoom")).toBeVisible();
    const after = await total(page);
    expect(after).toBeGreaterThan(0);
    expect(after).toBeLessThan(before / 20);
    await page.getByText("reset zoom").click();
    await expect(page).not.toHaveURL(/from=/);
  });

  test("a row opens its details, and attributes become filters", async ({ page }) => {
    const ok = watchErrors(page);
    await search(page, { q: "level:error service:payments" });
    await page.getByTestId("result-row").first().click();
    const drawer = page.getByTestId("drawer");
    await expect(drawer).toBeVisible();
    await expect(drawer).toContainText("payment gateway timeout");
    await expect(drawer.locator("dt", { hasText: "payment.provider" })).toBeVisible();
    await expect(drawer).not.toContainText("[object");
    await drawer.getByRole("button", { name: "Filter payment.provider" }).click();
    await expect(page).toHaveURL(/payment.provider%3Astripe/);
    ok();
  });
});

test.describe("trace search", () => {
  test("duration filter is in milliseconds", async ({ page }) => {
    await search(page, { source: "traces", q: "duration:>1000" });
    expect(await total(page)).toBeGreaterThan(0);
    for (const row of await page.getByTestId("result-row").all()) await expect(row.locator("td").last()).toContainText(/ s$/);
  });

  test("status and numeric attribute filters", async ({ page }) => {
    await search(page, { source: "traces", q: "status:error http.response.status_code:>=500" });
    expect(await total(page)).toBeGreaterThan(0);
    for (const row of await page.getByTestId("result-row").all()) await expect(row).toContainText("Error");
    await expect(page.getByTestId("histogram").locator(".legend")).toContainText("Error");
  });
});
