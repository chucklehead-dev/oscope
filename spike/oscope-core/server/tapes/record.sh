#!/usr/bin/env bash
# Record the TUI with VHS against a freshly seeded oscope, then check the
# text recording. Needs: vhs v0.11.0, ttyd, ffmpeg, a Chromium on PATH (as
# chromium), and OSCOPE_BIN / OSCOPE_TUI_BIN (default ../target/release).
#
# Pin vhs v0.11.0: v0.12.0 cancels its context before rendering, so every
# ffmpeg call fails before it starts and no GIF is written, with exit 0.
#   go install github.com/charmbracelet/vhs@v0.11.0
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
bin_dir=${BIN_DIR:-$here/../../target/release}
oscope=${OSCOPE_BIN:-$bin_dir/oscope}
tui_dir=$(dirname "${OSCOPE_TUI_BIN:-$bin_dir/oscope-tui}")
port=${PORT:-18580}
db=$(mktemp -d)

"$oscope" --db "$db/store" --listen "127.0.0.1:$port" --seed-minutes 60 --seed-rpm 300 --live-rps 4 >"$db/server.log" 2>&1 &
server=$!
trap 'kill $server 2>/dev/null; rm -rf "$db"' EXIT
for _ in $(seq 1 120); do curl -sf "http://127.0.0.1:$port/healthz" >/dev/null && break; sleep 0.5; done

export OSCOPE_URL="http://127.0.0.1:$port" PATH="$tui_dir:$PATH"
case "$(vhs --version 2>/dev/null)" in
  *v0.12.0*) echo "vhs v0.12.0 writes no output (see header); install v0.11.0" >&2; exit 2 ;;
esac
cd "$here"
mkdir -p ../e2e/test-results/vhs
vhs oscope-tui-check.tape
vhs oscope-tui.tape

# the text recording must show each step of the session
ascii=../e2e/test-results/vhs/check.ascii
fail=0
for want in "level:error service:payments" "payment gateway timeout" "t: open trace" "waterfall" "! POST /charge" \
            "gateway timeout after 2000ms" "logs in this trace" "p95 latency" "inventory"; do
  if grep -qF -- "$want" "$ascii"; then echo "ok    $want"; else echo "FAIL  $want"; fail=1; fi
done
ls -la ../docs/oscope-tui-vhs.gif
exit $fail
