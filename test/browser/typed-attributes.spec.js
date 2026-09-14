const {test, expect} = require("@playwright/test");
const {emitTypedBoolean, emitTypedInt64, emitTypedLocations} = require("./helpers");

const coverageLabels = {
  valid: "Valid typed value",
  empty: "Present empty string",
  absent: "Attribute absent",
  invalid: "Invalid typed value",
  fallback: "Historical fallback value",
  unavailable: "Historical value unavailable",
  total: "Total rows",
};

async function coverageCount(coverage, label) {
  const row = coverage.getByRole("row", {name: new RegExp(`^${label} `)});
  return Number(await row.getByRole("cell").last().textContent());
}

test("summarizes exact typed Int64 ranges with honest historical coverage", async ({page, request, baseURL}) => {
  await emitTypedInt64(request, baseURL);
  await page.goto("/oscope");

  const aggregate = page.getByRole("form", {name: "Typed Int64 trace attribute aggregate"});
  await aggregate.getByLabel("Minimum, inclusive").fill("10");
  await aggregate.getByLabel("Maximum, exclusive").fill("20");
  await aggregate.getByLabel("Group by").selectOption("service-name");
  await aggregate.getByRole("checkbox", {name: "MIN", exact: true}).uncheck();
  await aggregate.getByRole("checkbox", {name: "MAX", exact: true}).uncheck();
  await aggregate.getByRole("checkbox", {name: "AVG", exact: true}).uncheck();
  await aggregate.getByRole("button", {name: "Summarize typed values"}).click();

  const canonical = new URL(page.url());
  expect(canonical.searchParams.get("mode")).toBe("typed-span-int64-aggregate");
  expect(canonical.searchParams.get("typed-field-id")).toMatch(/^attribute_[0-9a-f]{20}$/);
  expect(canonical.searchParams.get("typed-attribute-key")).toBe("game.score");
  expect(canonical.searchParams.get("typed-attribute-type")).toBe("int64");
  expect(canonical.searchParams.get("typed-attribute-location")).toBe("span-attributes");
  expect(canonical.searchParams.get("typed-manifest-version")).toBe("1");
  expect(canonical.searchParams.get("typed-gte")).toBe("10");
  expect(canonical.searchParams.get("typed-lt")).toBe("20");
  expect(canonical.searchParams.get("group-by")).toBe("service-name");
  expect(canonical.searchParams.get("aggregate-count")).toBe("1");
  expect(canonical.searchParams.has("aggregate-min")).toBe(false);

  const coverage = page.getByRole("region", {name: "Typed value coverage"});
  await expect(coverage.getByRole("row", {name: /^Historical value unavailable .* 1$/})).toBeVisible();
  const counts = {};
  for (const [key, label] of Object.entries(coverageLabels)) {
    counts[key] = await coverageCount(coverage, label);
  }
  expect(counts.valid).toBe(3);
  expect(counts.invalid).toBe(1);
  expect(counts.fallback).toBe(1);
  expect(counts.unavailable).toBe(1);
  expect(counts.total).toBe(["valid", "empty", "absent", "invalid", "fallback", "unavailable"]
    .reduce((sum, status) => sum + counts[status], 0));
  const results = page.locator("#oscope-screen > section.panel").last();
  await expect(results.getByRole("row", {name: /oscope-typed-alpha 2/})).toBeVisible();
  await expect(results).not.toContainText("oscope-typed-beta");

  const fieldId = canonical.searchParams.get("typed-field-id");
  const bad = await page.goto(`/oscope?mode=typed-span-int64-aggregate&typed-field-id=${fieldId}&typed-gte=20&typed-lt=20&aggregates-present=1&aggregate-count=1`);
  expect(bad.status()).toBe(400);
  await expect(page.getByRole("heading", {name: "Invalid typed aggregate"})).toBeVisible();

  canonical.searchParams.set("typed-manifest-version", "2");
  const stale = await page.goto(canonical.toString());
  expect(stale.status()).toBe(409);
  await expect(page.getByRole("heading", {name: "Typed schema changed"})).toBeVisible();
});

test("filters and displays false with an exact saved Boolean binding", async ({page, request, baseURL}) => {
  await emitTypedBoolean(request, baseURL);
  await page.goto("/oscope");

  const form = page.getByRole("form", {name: "Typed boolean trace attribute filter"});
  await form.getByLabel("Value").selectOption("false");
  await form.getByRole("button", {name: "Filter typed spans"}).click();

  const canonical = new URL(page.url());
  expect(canonical.searchParams.get("mode")).toBe("typed-span-filter");
  expect(canonical.searchParams.get("typed-field-id")).toMatch(/^attribute_[0-9a-f]{20}$/);
  expect(canonical.searchParams.get("typed-attribute-key")).toBe("game.ready");
  expect(canonical.searchParams.get("typed-attribute-type")).toBe("boolean");
  expect(canonical.searchParams.get("typed-attribute-location")).toBe("span-attributes");
  expect(canonical.searchParams.get("typed-manifest-version")).toBe("1");
  expect(canonical.searchParams.get("typed-operator")).toBe("eq");
  expect(canonical.searchParams.get("typed-value")).toBe("false");
  const savedURL = canonical.toString();

  const results = page.locator("#oscope-screen > section.panel").last();
  await expect(results.getByRole("row", {name: /ready\.false\.first false/})).toBeVisible();
  await expect(results.getByRole("row", {name: /ready\.false\.second false/})).toBeVisible();
  await expect(results).not.toContainText("ready.true");
  await expect(results.getByRole("row")).toHaveCount(3);

  const coverage = page.getByRole("region", {name: "Typed value coverage"});
  const counts = {};
  for (const [key, label] of Object.entries(coverageLabels)) {
    counts[key] = await coverageCount(coverage, label);
  }
  expect(counts.valid).toBe(3);
  expect(counts.invalid).toBe(1);
  expect(counts.fallback).toBe(1);
  expect(counts.unavailable).toBe(1);
  expect(counts.absent).toBeGreaterThanOrEqual(1);
  expect(counts.total).toBe(["valid", "empty", "absent", "invalid", "fallback", "unavailable"]
    .reduce((sum, status) => sum + counts[status], 0));

  await page.reload();
  expect(page.url()).toBe(savedURL);
  await expect(page.getByRole("heading", {name: "Typed Boolean spans · game.ready · Span"})).toBeVisible();
  await expect(page.getByRole("form", {name: "Typed boolean trace attribute filter"})
    .getByLabel("Value")).toHaveValue("false");
  await expect(results.getByRole("row", {name: /ready\.false\.first false/})).toBeVisible();

  const invalid = await page.goto(`/oscope?mode=typed-span-filter&typed-field-id=${canonical.searchParams.get("typed-field-id")}&typed-value=FALSE`);
  expect(invalid.status()).toBe(400);
  await expect(page.getByRole("heading", {name: "Invalid typed filter"})).toBeVisible();

  canonical.searchParams.set("typed-manifest-version", "2");
  const stale = await page.goto(canonical.toString());
  expect(stale.status()).toBe(409);
  await expect(page.getByRole("heading", {name: "Typed schema changed"})).toBeVisible();
});

test("keeps equal resource, scope, and span keys distinct", async ({page, request, baseURL}) => {
  await emitTypedLocations(request, baseURL);
  for (const [label, location, value] of [
    ["demo.shared (String) - Resource", "resource-attributes", "resource-value"],
    ["demo.shared (String) - Scope", "scope-attributes", "scope-value"],
    ["demo.shared (String) - Span", "span-attributes", "span-value"],
  ]) {
    await page.goto("/oscope");
    const form = page.getByRole("form", {name: "Typed string trace attribute filter"});
    await form.getByLabel("Typed trace attribute").selectOption({label});
    await form.getByLabel("Value").fill(value);
    await form.getByRole("button", {name: "Filter typed spans"}).click();
    const canonical = new URL(page.url());
    expect(canonical.searchParams.get("typed-attribute-location")).toBe(location);
    await expect(page.getByRole("heading", {name: new RegExp(`Typed String spans .* ${label.split(" - ")[1]}`)})).toBeVisible();
    await expect(page.locator("#oscope-screen > section.panel").last()).toContainText(value);
    if (location === "scope-attributes") {
      await expect(page.getByRole("region", {name: "Typed value coverage"})
        .getByRole("row", {name: /^Historical value unavailable .* [1-9][0-9]*$/})).toBeVisible();
    }
  }
});
