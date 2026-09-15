const {test, expect} = require("@playwright/test");
const {emitTypedLogs} = require("./helpers");

test("queries projected typed logs with an exact saved binding and coverage", async ({page, request, baseURL}) => {
  const logBaseURL = await emitTypedLogs(request, baseURL);
  await page.goto(`${logBaseURL}/oscope`);

  const form = page.getByRole("form", {name: "Typed int64 log attribute filter"});
  await form.getByLabel("Operator").selectOption("gte");
  await form.getByLabel("Value").fill("9007199254740993");
  await form.getByRole("button", {name: "Filter typed logs"}).click();

  const canonical = new URL(page.url());
  expect(canonical.searchParams.get("mode")).toBe("typed-log-filter");
  expect(canonical.searchParams.get("typed-attribute-key")).toBe("job.attempt");
  expect(canonical.searchParams.get("typed-attribute-type")).toBe("int64");
  expect(canonical.searchParams.get("typed-attribute-location")).toBe("log-attributes");
  expect(canonical.searchParams.get("typed-manifest-version")).toBe("1");
  expect(canonical.searchParams.get("typed-value")).toBe("9007199254740993");

  await expect(page.getByRole("heading", {name: "Typed Int64 logs - job.attempt"}))
    .toBeVisible();
  const results = page.locator("#oscope-screen > section.panel").last();
  await expect(results).toContainText("exact large integer");
  await expect(results).toContainText("9007199254740993");
  await expect(results).not.toContainText("small integer");

  const coverage = page.getByRole("region", {name: "Typed value coverage"});
  for (const label of ["Valid typed value", "Attribute absent", "Invalid typed value",
                       "Historical fallback value", "Historical value unavailable",
                       "Total rows"]) {
    await expect(coverage.getByText(label, {exact: true})).toBeVisible();
  }

  const savedURL = canonical.toString();
  await page.reload();
  expect(page.url()).toBe(savedURL);
  await expect(results).toContainText("9007199254740993");

  canonical.searchParams.set("typed-manifest-version", "2");
  const stale = await page.goto(canonical.toString());
  expect(stale.status()).toBe(409);
  await expect(page.getByRole("heading", {name: "Typed schema changed"})).toBeVisible();
});
