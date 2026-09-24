// Starts a fresh `oscope` with an hour of seeded multi-service traffic and
// runs the browser suite against it. OSCOPE_BIN points at the built binary.
const { defineConfig, devices } = require("@playwright/test");
const path = require("path");

const port = Number(process.env.OSCOPE_E2E_PORT || 18480);
const bin = process.env.OSCOPE_BIN || path.resolve(__dirname, "../../target/release/oscope");
const db = path.resolve(__dirname, ".data");

module.exports = defineConfig({
  testDir: "tests",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  workers: 1,
  fullyParallel: false,
  reporter: [["list"]],
  outputDir: "test-results",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: `http://127.0.0.1:${port}`,
    viewport: { width: 1440, height: 900 },
    colorScheme: "dark",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: `rm -rf "${db}" && "${bin}" --db "${db}" --listen 127.0.0.1:${port} --seed-minutes 60 --seed-rpm 300 --seed 7`,
    url: `http://127.0.0.1:${port}/healthz`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
  projects: [
    { name: "e2e", testMatch: /\.spec\.js$/, testIgnore: /docs\.spec\.js$/ },
    { name: "docs", testMatch: /docs\.spec\.js$/ },
  ],
});
