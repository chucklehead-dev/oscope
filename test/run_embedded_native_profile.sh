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
umask 077
work_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/oscope-embedded-native.XXXXXXXX")
child_output="$work_root/child-output"
mkdir -p "$work_root/fixture-root"
seal="$work_root/generation.seal"
marker="$work_root/generation.marker"
completed=false

write-receipt() {
  local result=$1
  case "$result" in
    passed)
      printf '%s\n' '{:oscope.embedded.native.receipt/version 1 :result :passed :checks #{:generation-match :one-sdk-owner :terminal-unavailable :v1-unchanged :v2-preterminal}}' > "$receipt"
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

run_fixture() {
  local mode=$1
  # :test is intentionally supplied by the minimal fixture deps.edn, not the
  # repository root. Keep alias resolution and process ownership in this
  # subshell so the manual gate cannot silently run a root suite.
  timeout --signal=TERM --kill-after=5s 180s bash -c '
    fixture_dir=$1
    jolt_bin=$2
    mode=$3
    fixture_root=$4
    seal=$5
    marker=$6
    cd -- "$fixture_dir"
    exec "$jolt_bin" -Srepro -M:test "$mode" "$fixture_root" "$seal" "$marker"
  ' bash "$fixture_dir" "$jolt_bin" "$mode" "$work_root/fixture-root" "$seal" "$marker" \
    >> "$child_output" 2>&1
}

# Do not accept an early or preexisting proof marker. The writer has no marker
# argument, and only the fresh reader can create exactly one final marker.
test ! -e "$marker"
if run_fixture writer \
  && test -f "$seal" && test ! -L "$seal" \
  && test "$(stat -c %a "$seal")" = 600 \
  && test ! -e "$marker" \
  && run_fixture reader \
  && test -f "$marker" && test ! -L "$marker" \
  && test "$(wc -l < "$marker")" = 1 \
  && test "$(cat "$marker")" = generation-match \
  && run_fixture mutant \
  && ! rg -q --fixed-strings -e application-secret -e remote-secret \
       -e fixture.confirmed -e authoritative "$child_output"
then
  write-receipt passed
  completed=true
else
  write-receipt failed
  exit 1
fi
