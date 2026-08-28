#!/bin/sh
# Build and briefly run the self-contained receiver. This catches namespaces
# incorrectly inherited from the compiler image as "already loaded": source
# mode masks those missing definitions by loading the namespace on demand.
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_BIN:-jolt}
toolchain=${JOLT_TOOLCHAIN:-}
lib=${JOLT_CHDB_LIB:?set JOLT_CHDB_LIB to libchdb.so}
tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-standalone.XXXXXX")
pid=

cleanup() {
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT INT TERM

run_jolt() {
  if [ -n "$toolchain" ]; then
    "$toolchain" "$jolt" "$@"
  else
    "$jolt" "$@"
  fi
}

cd "$repo"
run_jolt build -m oscope.server-main -o "$tmp/oscope"

env JOLT_CHDB_LIB="$lib" \
    OSCOPE_PORT=0 \
    OSCOPE_CHDB_SPEC="chdb:$tmp/data" \
    "$tmp/oscope" >"$tmp/server.log" 2>&1 &
pid=$!

i=0
until grep -q '^oscope receiving OTLP/HTTP' "$tmp/server.log" 2>/dev/null; do
  if ! kill -0 "$pid" 2>/dev/null; then
    cat "$tmp/server.log" >&2
    echo "FAIL: standalone oscope exited before readiness" >&2
    exit 1
  fi
  i=$((i + 1))
  if [ "$i" -ge 300 ]; then
    cat "$tmp/server.log" >&2
    echo "FAIL: standalone oscope readiness timed out" >&2
    exit 1
  fi
  sleep 0.1
done

# Startup continues after the readiness line while the server and embedded DB
# fibers settle. The v0.7.27 missing-CLI-closure failure appears in this window.
sleep 1
if ! kill -0 "$pid" 2>/dev/null; then
  cat "$tmp/server.log" >&2
  echo "FAIL: standalone oscope exited after readiness" >&2
  exit 1
fi
if grep -Eq 'Attempting to call unbound fn|ThreadStatus: current_thread contains invalid address|<Fatal>' \
     "$tmp/server.log"; then
  cat "$tmp/server.log" >&2
  echo "FAIL: standalone oscope reported a runtime/native failure" >&2
  exit 1
fi

echo "PASS: standalone oscope build and startup"
