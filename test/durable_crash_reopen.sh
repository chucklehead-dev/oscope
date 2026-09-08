#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain="$workspace/tools/jolt-with-chez-10.4.1"
scenario="$repo_root/test/durable-process"
server_binary="$scenario/target/oscope-durable-server"
verify_binary="$scenario/target/oscope-durable-crash-verify"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-crash.XXXXXX")
pid=

: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"

cleanup() {
  if [ -n "$pid" ]; then
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT INT TERM

if ! (
  cd "$scenario"
  "$toolchain" jolt build -m oscope.durable-server-main \
    -o target/oscope-durable-server
  "$toolchain" jolt build -m oscope.durable-crash-verify \
    -o target/oscope-durable-crash-verify
) >"$tmp/build.log" 2>&1; then
  cat "$tmp/build.log" >&2
  exit 1
fi

start_server() {
  instance=$1
  log=$2
  server_log=$log
  env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" \
      OSCOPE_PORT=0 \
      OSCOPE_DURABLE_ROOT="$tmp/store" \
      OSCOPE_DURABLE_INSTANCE="$instance" \
      OSCOPE_DURABLE_LEASE_TTL_MS=300 \
      OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS=50 \
      "$server_binary" >"$log" 2>&1 &
  pid=$!

  attempts=0
  while :; do
    port=$(sed -n 's#.*127\.0\.0\.1:\([0-9][0-9]*\)/oscope.*#\1#p' "$log" | tail -1)
    if [ -n "$port" ]; then
      return
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      cat "$log" >&2
      echo "FAIL: Durable server exited before readiness" >&2
      exit 1
    fi
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 300 ]; then
      cat "$log" >&2
      echo "FAIL: Durable server readiness timed out" >&2
      exit 1
    fi
    sleep 0.1
  done
}

post_trace() {
  trace_id=$1
  body=$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-crash"}}]},"scopeSpans":[{"scope":{"name":"oscope.durable-crash"},"spans":[{"traceId":"%s","spanId":"2222222222222222","name":"durable.crash","startTimeUnixNano":"1770000000000000000","endTimeUnixNano":"1770000000001000000"}]}]}]}' "$trace_id")
  status=$(curl -sS -o "$tmp/response" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary "$body" "http://127.0.0.1:$port/v1/traces")
  if [ "$status" != 200 ]; then
    cat "$tmp/response" >&2
    cat "$server_log" >&2
    echo "FAIL: Durable ingest returned HTTP $status" >&2
    exit 1
  fi
}

crash_server() {
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  pid=
  # The next process must take over through ordinary lease expiry, not force.
  sleep 1
}

start_server first "$tmp/first.log"
post_trace 11111111111111111111111111111111
crash_server

start_server second "$tmp/second.log"
post_trace 33333333333333333333333333333333
crash_server

env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" "$verify_binary" "$tmp/store" 2
