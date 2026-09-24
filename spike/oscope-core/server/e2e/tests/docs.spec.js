// Screenshots and GIF frames for the docs. Run with `npm run docs`.
const { test, expect } = require("@playwright/test");
const { snapshot } = require("./tui-helpers");
const fs = require("fs");
const path = require("path");

const shots = path.resolve(__dirname, "../../docs/screenshots");
const frames = path.resolve(__dirname, "../test-results/gif-frames");

async function ready(page) {
  await expect(page.locator(".empty", { hasText: "Loading" })).toHaveCount(0);
  await page.waitForTimeout(150);
}

test("screenshots", async ({ page }) => {
  fs.mkdirSync(shots, { recursive: true });
  const snap = (name) => page.screenshot({ path: path.join(shots, name) });

  await page.goto("/#/search?source=logs&range=1h");
  await ready(page);
  await snap("01-log-search.png");

  await page.goto("/#/search?" + new URLSearchParams({ source: "logs", range: "1h", q: "level:error" }));
  await ready(page);
  await snap("02-errors-with-facets.png");

  await page.getByTestId("result-row").filter({ hasText: "payment gateway timeout" }).first().click();
  await expect(page.getByTestId("drawer")).toBeVisible();
  await snap("03-log-detail.png");

  await page.getByTestId("open-trace").click();
  await expect(page.getByTestId("waterfall")).toBeVisible();
  await page.getByTestId("span-row").filter({ hasText: "POST /charge" }).click();
  await snap("04-trace-waterfall.png");

  await page.goto("/#/search?" + new URLSearchParams({ source: "traces", range: "1h", q: "duration:>500 status:error" }));
  await ready(page);
  await snap("05-slow-failed-spans.png");

  await page.goto("/#/services?range=1h&open=checkout,payments");
  await expect(page.getByTestId("services")).toBeVisible();
  await snap("06-services.png");
});

test("gif frames", async ({ page }) => {
  fs.rmSync(frames, { recursive: true, force: true });
  fs.mkdirSync(frames, { recursive: true });
  await page.setViewportSize({ width: 1200, height: 720 });
  let n = 0;
  // file name carries the frame's display time in ms
  const frame = async (ms) => page.screenshot({ path: path.join(frames, `${String(n++).padStart(3, "0")}_${ms}.png`) });

  await page.goto("/#/search?source=logs&range=1h");
  await ready(page);
  await frame(1800);

  const input = page.getByTestId("query");
  await input.click();
  await input.fill("");
  const q = 'service:payments level:error';
  for (let i = 1; i <= q.length; i++) {
    await input.fill(q.slice(0, i));
    if (i % 4 === 0 || i === q.length) await frame(110);
  }
  await page.getByTestId("run").click();
  await ready(page);
  await frame(1800);

  await page.getByTestId("result-row").first().click();
  await expect(page.getByTestId("drawer")).toBeVisible();
  await frame(1800);

  await page.getByTestId("open-trace").click();
  await expect(page.getByTestId("waterfall")).toBeVisible();
  await frame(1500);
  await page.getByTestId("span-row").filter({ hasText: "POST /charge" }).click();
  await frame(2200);

  await page.keyboard.press("Escape");
  await page.goto("/#/services?range=1h");
  await expect(page.getByTestId("services")).toBeVisible();
  await frame(1500);
  await page.locator('[data-service="payments"]').click();
  await expect(page.locator("tr.endpoints")).toBeVisible();
  await frame(2200);

  await page.goto("/#/search?" + new URLSearchParams({ source: "traces", range: "1h", q: "duration:>500 status:error" }));
  await ready(page);
  await frame(1800);
  await page.getByTestId("histogram").locator("svg .bucket").nth(45).click();
  await ready(page);
  await frame(2400);
});

test("tui screenshots and gif frames", async ({ page, baseURL }) => {
  const tuiFrames = path.resolve(__dirname, "../test-results/gif-frames-tui");
  fs.rmSync(tuiFrames, { recursive: true, force: true });
  fs.mkdirSync(tuiFrames, { recursive: true });
  let n = 0;
  const shot = async (name, keys, { file, ms } = {}) => {
    await page.goto(snapshot(baseURL, name, keys, "130x38"));
    const term = page.locator(".term");
    if (file) await term.screenshot({ path: path.join(shots, file) });
    if (ms) await term.screenshot({ path: path.join(tuiFrames, `${String(n++).padStart(3, "0")}_${ms}.png`) });
  };
  await shot("t-services", "s", { file: "tui-01-services.png", ms: 1800 });
  await shot("t-search", "", { file: "tui-02-search.png", ms: 1500 });
  const q = "level:error service:payments";
  for (let i = 4; i <= q.length; i += 6) await shot("t-type", "/ " + q.slice(0, i).replace(/ /g, " <space> "), { ms: 120 });
  await shot("t-errors", "/ " + q.replace(/ /g, " <space> ") + " <enter>", { ms: 1500 });
  await shot("t-detail", "/ " + q.replace(/ /g, " <space> ") + " <enter> <enter>", { file: "tui-03-detail.png", ms: 1800 });
  await shot("t-trace", "/ " + q.replace(/ /g, " <space> ") + " <enter> t", { ms: 1500 });
  await shot("t-span", "/ " + q.replace(/ /g, " <space> ") + " <enter> t <down> <down> <down> <down> <down> <down> <enter>", { file: "tui-04-trace.png", ms: 2400 });
});
