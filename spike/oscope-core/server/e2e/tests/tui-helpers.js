// Drive oscope-tui headlessly: replay a key script, render the final frame to
// HTML (--snapshot), and hand back its path.
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const bin = process.env.OSCOPE_TUI_BIN || path.resolve(__dirname, "../../../target/release/oscope-tui");

function snapshot(baseURL, name, keys = "", size = "140x42") {
  const dir = path.resolve(__dirname, "../test-results/tui");
  fs.mkdirSync(dir, { recursive: true });
  const out = path.join(dir, `${name}.html`);
  execFileSync(bin, ["--url", baseURL, "--range", "1h", "--size", size, "--snapshot", out, "--keys", keys], { stdio: "pipe" });
  return "file://" + out;
}

module.exports = { snapshot };
