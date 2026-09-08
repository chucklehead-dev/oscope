const base = require("./playwright.config");

module.exports = {
  ...base,
  timeout: 90_000,
  outputDir: "test-results/gif",
  use: {
    ...base.use,
    viewport: {width: 1280, height: 900},
    video: {mode: "on", size: {width: 1280, height: 900}},
  },
  projects: [{name: "gif", testMatch: /storyboard\.gif\.spec\.js/}],
};
