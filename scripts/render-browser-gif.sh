#!/usr/bin/env bash
set -euo pipefail

command -v ffmpeg >/dev/null || {
  echo "oscope demo GIF requires ffmpeg" >&2
  exit 1
}

npx playwright test --config=playwright.gif.config.js

ffmpeg -hide_banner -loglevel error -y \
  -ss 0.4 \
  -i test-results/oscope-checkout-tour.webm \
  -vf "fps=10,scale=960:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  docs/demo/oscope-checkout-tour.gif
