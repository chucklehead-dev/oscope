// Assemble NNN_<ms>.png frames into a GIF (default: the web tour).
// Pure JS (gifenc + pngjs): no ffmpeg needed.
const fs = require("fs");
const path = require("path");
const { GIFEncoder, quantize, applyPalette } = require("gifenc");
const { PNG } = require("pngjs");

// usage: node make-gif.js [frames-dir] [out.gif]
const dir = path.resolve(process.argv[2] || path.join(__dirname, "../test-results/gif-frames"));
const out = path.resolve(process.argv[3] || path.join(__dirname, "../../docs/oscope-tour.gif"));
const files = fs.readdirSync(dir).filter((f) => f.endsWith(".png")).sort();
if (!files.length) throw new Error("no frames: run the docs project first");

// One palette for the whole tour keeps colors stable between frames.
const sample = [];
for (const f of files.filter((_, i) => i % 3 === 0)) {
  const { data } = PNG.sync.read(fs.readFileSync(path.join(dir, f)));
  for (let i = 0; i < data.length; i += 4 * 16) sample.push(data[i], data[i + 1], data[i + 2], 255);
}
const palette = quantize(new Uint8Array(sample), 128);

const gif = GIFEncoder();
for (const f of files) {
  const png = PNG.sync.read(fs.readFileSync(path.join(dir, f)));
  const index = applyPalette(png.data, palette);
  const delay = Number(f.split("_")[1].replace(".png", ""));
  gif.writeFrame(index, png.width, png.height, { palette, delay });
}
gif.finish();
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, gif.bytes());
console.log(`wrote ${out}: ${files.length} frames, ${(fs.statSync(out).size / 1024).toFixed(0)} KiB`);
