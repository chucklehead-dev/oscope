const {test, expect} = require("@playwright/test");
const {emitCheckout, emitGaugeBuckets, openCheckoutTrace} = require("./helpers");

test("full-page workbench preserves contrast and long attributes responsively", async ({page, request, baseURL}, testInfo) => {
  const traceId = "abababababababababababababababab";
  const label = "samizdat.application.request.lifecycle.completion.status";
  const value = "complete / preserved <literal> & Unicode λ " + "x".repeat(160);
  const now = BigInt(Date.now()) * 1000000n;
  const response = await request.post(`${baseURL}/v1/traces`, {data: {
    resourceSpans: [{resource: {attributes: [{key: "service.name", value: {stringValue: "theme-regression"}}]},
      scopeSpans: [{scope: {name: "oscope.theme-regression"}, spans: [{
        traceId, spanId: "abababababababab", name: "theme regression", kind: 1,
        startTimeUnixNano: String(now - 1000000n), endTimeUnixNano: String(now),
        attributes: [{key: label, value: {stringValue: value}}], status: {code: 1},
      }]}]}],
  }});
  expect(response.status()).toBe(200);
  await page.goto("/oscope/telemetry");
  await page.screenshot({path: testInfo.outputPath("workbench-index.png"), fullPage: true});
  // Read actual computed styles: no injected CSS or repaired screenshot.
  const contrasts = await page.locator(".otel-header h1, .otel-eyebrow, .oscope-nav a").evaluateAll(elements => {
    const luminance = color => {
      const rgb = color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => {
        const s = v / 255; return s <= .04045 ? s / 12.92 : ((s + .055) / 1.055) ** 2.4;
      });
      return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
    };
    // Transparent body/html paint the browser's white canvas, not black.
    // Walk to the actual opaque page background rather than dropping alpha.
    let backgroundColor = "rgb(255, 255, 255)";
    for (let node = document.body; node; node = node.parentElement) {
      const color = getComputedStyle(node).backgroundColor;
      if (color !== "transparent" && !/rgba\([^)]*,\s*0\s*\)/.test(color)) {
        backgroundColor = color; break;
      }
    }
    const background = luminance(backgroundColor);
    return elements.map(el => {
      const foreground = luminance(getComputedStyle(el).color);
      return (Math.max(foreground, background) + .05) / (Math.min(foreground, background) + .05);
    });
  });
  expect(contrasts.length).toBeGreaterThanOrEqual(5);
  console.log("workbench computed contrast ratios", contrasts);
  for (const contrast of contrasts) expect(contrast).toBeGreaterThanOrEqual(4.5);
  await expect(page.locator("html")).toHaveClass(/otel-page/);
  await page.goto(`/oscope/telemetry/traces/${traceId}`);
  const term = page.locator(".otel-span-meta dt").filter({hasText: label});
  await expect(term).toHaveCount(1);
  const definition = term.locator("..").locator("dd");
  for (const width of [1440, 390]) {
    await page.setViewportSize({width, height: 1000});
    await expect(term).toHaveText(label);
    await expect(definition).toHaveText(value);
    const layout = await term.locator("..").evaluate(row => {
      const dt = row.querySelector("dt").getBoundingClientRect();
      const dd = row.querySelector("dd").getBoundingClientRect();
      return {dt: {x: dt.x, y: dt.y, bottom: dt.bottom}, dd: {x: dd.x, y: dd.y},
        overflow: document.documentElement.scrollWidth > innerWidth};
    });
    expect(layout.overflow).toBe(false);
    if (width === 390) expect(layout.dd.y).toBeGreaterThanOrEqual(layout.dt.bottom);
    else expect(layout.dd.x).toBeGreaterThan(layout.dt.x);
    await page.screenshot({path: testInfo.outputPath(`workbench-${width}.png`), fullPage: true});
  }
});

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

test("rebinds one semantic Plotje recipe when its telemetry changes", async ({page, request, baseURL}) => {
  const metricName = "demo.dynamic.queue.depth";
  await emitGaugeBuckets(request, baseURL, {
    metricName,
    buckets: [[2, 4], [6, 8]],
  });
  await page.goto("/oscope?signal=metrics&field=metric-name&window=15m&limit=10");
  await page.getByRole("link", {name: "Edit this chart"}).click();

  const editor = page.getByLabel("Chart specification");
  const recipe = `{:title "Queue depth over time"
 :data {:source :telemetry-query
        :query {:signal :metrics :window :15m :bucket :5m
                :group-by [:service-name]
                :filters [{:field :metric-name :op :eq
                           :value "${metricName}"}]
                :series [{:as :average :op :avg :field :value}
                         {:as :p95 :op :percentile :field :value
                          :percentile 95}]
                :calculations [{:as :tail-gap :op :subtract
                                :args [:p95 :average]}]
                :limit 20}
        :select [:bucket-start-unix-nano :service-name :average :p95 :tail-gap]}
  :layers [{:mark :line :x :bucket-start-unix-nano
           :y :average :color :service-name}
          {:mark :line :x :bucket-start-unix-nano
           :y :p95 :color :service-name}
          {:mark :line :x :bucket-start-unix-nano
           :y :tail-gap :color :service-name}]}`;
  await editor.fill(recipe);
  await expect(editor).toHaveValue(recipe);

  await expect(page.locator("#plotje-preview")).toContainText("Queue depth over time");
  const previewLines = page.locator("#plotje-preview svg polyline");
  await expect(previewLines).toHaveCount(3);
  const firstPreview = await previewLines.evaluateAll((lines) =>
    lines.map((line) => line.getAttribute("points")));
  expect(firstPreview.every((points) => points.trim().split(/\s+/).length >= 2)).toBe(true);

  await expect(editor).toHaveValue(/:bucket :5m/);
  await expect(editor).toHaveValue(/:group-by \[:service-name\]/);
  await expect(editor).toHaveValue(/:as :p95 :op :percentile/);
  await expect(editor).toHaveValue(/:as :tail-gap :op :subtract/);
  await expect(editor).toHaveValue(/:select \[:bucket-start-unix-nano :service-name :average :p95 :tail-gap\]/);
  await expect(editor).not.toHaveValue(/:data\s+\[/);

  await emitGaugeBuckets(request, baseURL, {
    metricName,
    buckets: [[100, 120], [1, 2]],
  });
  await editor.evaluate((input) =>
    input.dispatchEvent(new Event("input", {bubbles: true})));

  await expect(editor).toHaveValue(recipe);
  await expect(page.locator("#plotje-preview")).toContainText("Queue depth over time");
  await expect(page.locator("#plotje-preview")).not.toContainText("Spec error");
  await expect(previewLines).toHaveCount(3);
  await expect.poll(async () => previewLines.evaluateAll((lines) =>
    lines.map((line) => line.getAttribute("points")))).not.toEqual(firstPreview);
  const reboundPreview = await previewLines.evaluateAll((lines) =>
    lines.map((line) => line.getAttribute("points")));
  expect(reboundPreview).toHaveLength(3);
  expect(reboundPreview.every((points) =>
    points.trim().split(/\s+/).length >= 2)).toBe(true);
  expect(reboundPreview).not.toEqual(firstPreview);
  await expect(editor).toHaveValue(recipe);
  await expect(editor).not.toHaveValue(/:data\s+\[/);
});
