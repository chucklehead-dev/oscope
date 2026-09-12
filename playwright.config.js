const {defineConfig, devices} = require("@playwright/test");

const port = Number(process.env.OSCOPE_E2E_PORT || 18318);
const baseURL = `http://127.0.0.1:${port}`;
const joltCommand = process.env.JOLT_WRAPPER
  ? `${process.env.JOLT_WRAPPER} jolt`
  : "jolt";

module.exports = defineConfig({
  testDir: "test/browser",
  timeout: 60_000,
  expect: {timeout: 10_000},
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  outputDir: "test-results",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    viewport: {width: 1440, height: 1000},
    colorScheme: "dark",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: `${joltCommand} -M:typed-browser-server`,
    url: `${baseURL}/healthz`,
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      OSCOPE_PORT: String(port),
    },
  },
  projects: [
    {
      name: "chromium",
      testMatch: /(?:oscope|typed-attributes)\.spec\.js/,
    },
    {
      name: "docs",
      testMatch: /storyboard\.screenshots\.spec\.js/,
    },
  ],
});
