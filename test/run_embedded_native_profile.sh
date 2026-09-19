#!/usr/bin/env bash
set -euo pipefail

# Execute the real minimal embedded fixture without forwarding child output to
# CI.  A failed exporter/fixture may contain application-controlled material;
# the only persisted evidence is the categorical receipt below.
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jolt_bin=${JOLT_BIN:-jolt}
receipt=${OSCOPE_EMBEDDED_NATIVE_RECEIPT:?OSCOPE_EMBEDDED_NATIVE_RECEIPT is required}
fixture_dir="$repo_root/test/fixtures/minimal-embedded-app"

case "$receipt" in
  "${RUNNER_TEMP:-/nonexistent}"/*) ;;
  *) echo "receipt must be rooted in RUNNER_TEMP" >&2; exit 64 ;;
esac

mkdir -p "$(dirname -- "$receipt")"
work_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/oscope-embedded-native.XXXXXXXX")
child_output="$work_root/child-output"
mkdir -p "$work_root/fixture-root"
completed=false

write-receipt() {
  local result=$1
  case "$result" in
    passed)
      printf '%s\n' '{:oscope.embedded.native.receipt/version 1 :result :passed :checks #{:one-sdk-owner :terminal-unavailable :v1-unchanged :v2-preterminal}}' > "$receipt"
      ;;
    failed)
      printf '%s\n' '{:oscope.embedded.native.receipt/version 1 :result :failed :checks #{}}' > "$receipt"
      ;;
  esac
}

cleanup() {
  local status=$?
  if [[ "$completed" != true ]]; then
    write-receipt failed
  fi
  rm -rf -- "$work_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if timeout --signal=TERM --kill-after=5s 180s bash -c '
  # :test is intentionally supplied by the minimal fixture deps.edn, not the
  # repository root. Keep alias resolution and process ownership in this
  # subshell so the manual gate cannot silently run a root suite.
  fixture_dir=$1
  jolt_bin=$2
  fixture_root=$3
  cd -- "$fixture_dir"
  exec "$jolt_bin" -Srepro -M:test "$fixture_root"
' bash "$fixture_dir" "$jolt_bin" "$work_root/fixture-root" > "$child_output" 2>&1
then
  write-receipt passed
  completed=true
else
  write-receipt failed
  exit 1
fi
