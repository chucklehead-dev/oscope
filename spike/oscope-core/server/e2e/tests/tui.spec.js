const { test, expect } = require("@playwright/test");
const { snapshot } = require("./tui-helpers");

test.describe("terminal UI (oscope-tui, rendered headlessly)", () => {
  test("search lists newest events with a volume sparkline", async ({ page, baseURL }) => {
    await page.goto(snapshot(baseURL, "search"));
    const tui = page.getByTestId("tui");
    await expect(tui).toContainText("oscope");
    await expect(tui).toContainText(/300 of \d{2},?\d{3}/);
    await expect(tui).toContainText("frontend");
    await expect(tui).toContainText("errors in red");
  });

  test("a query filters, and Enter opens details", async ({ page, baseURL }) => {
    await page.goto(snapshot(baseURL, "errors", "/ level:error <space> service:payments <enter> <enter>"));
    const tui = page.getByTestId("tui");
    await expect(tui).toContainText("level:error service:payments");
    await expect(tui).toContainText("payment gateway timeout");
    await expect(tui).not.toContainText(" INFO ");
    await expect(tui).toContainText("details");
    await expect(tui).toContainText("payment.provider");
    await expect(tui).toContainText("t: open trace");
  });

  test("t opens the trace waterfall; span details show attributes", async ({ page, baseURL }) => {
    await page.goto(snapshot(baseURL, "trace", '/ "payment <space> gateway <space> timeout" <enter> t <down> <down> <down> <down> <down> <down> <enter>'));
    const tui = page.getByTestId("tui");
    await expect(tui).toContainText("8 spans");
    await expect(tui).toContainText("waterfall");
    await expect(tui).toContainText("! POST /charge");
    await expect(tui).toContainText("gateway timeout after 2000ms");
    await expect(tui).toContainText("logs in this trace");
  });

  test("s shows services with RED metrics", async ({ page, baseURL }) => {
    await page.goto(snapshot(baseURL, "services", "s"));
    const tui = page.getByTestId("tui");
    for (const svc of ["frontend", "checkout", "inventory", "payments"]) await expect(tui).toContainText(svc);
    await expect(tui).toContainText("p95 latency");
    await expect(tui).toContainText(/\d+\.\d\d%/);
  });

  test("an invalid query is reported in the status line", async ({ page, baseURL }) => {
    await page.goto(snapshot(baseURL, "bad", "/ bad!key:1 <enter>"));
    await expect(page.getByTestId("tui")).toContainText("invalid field name");
  });
});
