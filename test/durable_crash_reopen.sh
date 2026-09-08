#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain="$workspace/tools/jolt-with-chez-10.4.1"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-crash.XXXXXX")
pid=
backend=${OSCOPE_DURABLE_CRASH_BACKEND:-local}
build_alias=${OSCOPE_DURABLE_CRASH_BUILD_ALIAS:-test-durable-s3-dev}
lease_ttl_ms=${OSCOPE_DURABLE_CRASH_LEASE_TTL_MS:-300}
heartbeat_interval_ms=${OSCOPE_DURABLE_CRASH_HEARTBEAT_INTERVAL_MS:-50}
takeover_wait_seconds=${OSCOPE_DURABLE_CRASH_TAKEOVER_WAIT_SECONDS:-1}

: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"

if [ -n "${JOLT_WRAPPER:-}" ]; then
  jolt_command=("$JOLT_WRAPPER" jolt)
elif [ -n "${JOLT_BIN:-}" ]; then
  jolt_command=("$JOLT_BIN")
else
  jolt_command=("$toolchain" jolt)
fi

cleanup() {
  if [ -n "$pid" ]; then
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT INT TERM

case "$backend" in
  local|s3) ;;
  *) echo "unsupported Durable crash backend: $backend" >&2; exit 2 ;;
esac
case "$build_alias" in
  test-durable-s3)
    scenario="$repo_root/test/durable-process-published"
    ;;
  test-durable-s3-dev)
    scenario="$repo_root/test/durable-process"
    ;;
  *) echo "unsupported Durable crash build alias: $build_alias" >&2; exit 2 ;;
esac
for value in "$lease_ttl_ms" "$heartbeat_interval_ms" \
             "$takeover_wait_seconds"; do
  case "$value" in
    ''|*[!0-9]*|0)
      echo "Durable crash timing values must be positive integers" >&2
      exit 2
      ;;
  esac
done
server_binary="$scenario/target/oscope-durable-server"
verify_binary="$scenario/target/oscope-durable-crash-verify"

mkdir -p "$scenario/target" "$tmp/scratch"
if ! (
  cd "$scenario"
  "${jolt_command[@]}" build -m oscope.durable-server-main \
    -o target/oscope-durable-server
  "${jolt_command[@]}" build -m oscope.durable-crash-verify \
    -o target/oscope-durable-crash-verify
) >"$tmp/build.log" 2>&1; then
  cat "$tmp/build.log" >&2
  exit 1
fi

durable_env=(
  "JOLT_CHDB_LIB=$JOLT_CHDB_LIB"
  "OSCOPE_DURABLE_BACKEND=$backend"
  "OSCOPE_DURABLE_SCRATCH_PARENT=$tmp/scratch"
)
if [ "$backend" = s3 ]; then
  for name in OSCOPE_DURABLE_S3_ENDPOINT OSCOPE_DURABLE_S3_BUCKET \
              OSCOPE_DURABLE_S3_REGION OSCOPE_DURABLE_S3_ACCESS_KEY \
              OSCOPE_DURABLE_S3_SECRET_KEY; do
    if [ -z "${!name:-}" ]; then
      echo "$name is required for the S3 crash scenario" >&2
      exit 2
    fi
  done
  durable_env+=(
    "OSCOPE_DURABLE_OBJECT_ID=${OSCOPE_DURABLE_OBJECT_ID:-telemetry-crash}"
    "OSCOPE_DURABLE_S3_ENDPOINT=$OSCOPE_DURABLE_S3_ENDPOINT"
    "OSCOPE_DURABLE_S3_BUCKET=$OSCOPE_DURABLE_S3_BUCKET"
    "OSCOPE_DURABLE_S3_PREFIX=${OSCOPE_DURABLE_S3_PREFIX:-process-crash}"
    "OSCOPE_DURABLE_S3_REGION=$OSCOPE_DURABLE_S3_REGION"
    "OSCOPE_DURABLE_S3_ACCESS_KEY=$OSCOPE_DURABLE_S3_ACCESS_KEY"
    "OSCOPE_DURABLE_S3_SECRET_KEY=$OSCOPE_DURABLE_S3_SECRET_KEY"
  )
  if [ -n "${OSCOPE_DURABLE_S3_SESSION_TOKEN:-}" ]; then
    durable_env+=(
      "OSCOPE_DURABLE_S3_SESSION_TOKEN=$OSCOPE_DURABLE_S3_SESSION_TOKEN"
    )
  fi
  case "${OSCOPE_DURABLE_S3_CREATE_BUCKET:-true}" in
    true) env "${durable_env[@]}" "$verify_binary" --create-s3-bucket ;;
    false) ;;
    *)
      echo "OSCOPE_DURABLE_S3_CREATE_BUCKET must be true or false" >&2
      exit 2
      ;;
  esac
else
  durable_env+=("OSCOPE_DURABLE_ROOT=$tmp/store")
fi

start_server() {
  instance=$1
  log=$2
  server_log=$log
  env "${durable_env[@]}" \
      OSCOPE_PORT=0 \
      OSCOPE_DURABLE_INSTANCE="$instance" \
      OSCOPE_DURABLE_LEASE_TTL_MS="$lease_ttl_ms" \
      OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS="$heartbeat_interval_ms" \
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
  span_name=$2
  body=$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-crash"}}]},"scopeSpans":[{"scope":{"name":"oscope.durable-crash"},"spans":[{"traceId":"%s","spanId":"2222222222222222","name":"%s","startTimeUnixNano":"1770000000000000000","endTimeUnixNano":"1770000000001000000"}]}]}]}' "$trace_id" "$span_name")
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
  sleep "$takeover_wait_seconds"
}

start_server first "$tmp/first.log"
post_trace 11111111111111111111111111111111 durable.crash.first
crash_server

start_server second "$tmp/second.log"
post_trace 33333333333333333333333333333333 durable.crash.second
crash_server

if [ "$backend" = s3 ]; then
  env "${durable_env[@]}" "$verify_binary" --verify-s3 2
else
  env "${durable_env[@]}" "$verify_binary" --verify-local "$tmp/store" 2
fi
