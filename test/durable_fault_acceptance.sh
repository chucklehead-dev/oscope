#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain="$workspace/tools/jolt-with-chez-10.4.1"
build_alias=${OSCOPE_DURABLE_FAULT_BUILD_ALIAS:-dev}

case "$build_alias" in
  dev)
    aspect_repo="$workspace/jolt-aspect-packs"
    scenario="$repo_root/test/durable-fault"
    verify_scenario="$repo_root/test/durable-process"
    ;;
  published)
    aspect_repo="$repo_root/.qualification/jolt-aspect-packs"
    scenario="$repo_root/test/durable-fault-published"
    verify_scenario="$repo_root/test/durable-process-published"
    ;;
  *) echo "unsupported Durable fault build alias: $build_alias" >&2; exit 2 ;;
esac
server_binary="$scenario/target/oscope-durable-fault-server"
verify_binary="$scenario/target/oscope-durable-fault-verify"
effects="$scenario/target/oscope-durable-fault-server.build/effects.edn"
report="$scenario/target/aspects.edn"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-fault.XXXXXX")
pid=

: "${JOLT_ASPECT_JOLT:?JOLT_ASPECT_JOLT must name the aspect-capable jolt executable}"
: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"

case "$JOLT_ASPECT_JOLT" in
  /*) ;;
  *) echo "JOLT_ASPECT_JOLT must be an absolute path" >&2; exit 2 ;;
esac
case "$JOLT_CHDB_LIB" in
  /*) ;;
  *) echo "JOLT_CHDB_LIB must be an absolute path" >&2; exit 2 ;;
esac

test -x "$JOLT_ASPECT_JOLT"
test -f "$JOLT_CHDB_LIB"
test -f "$aspect_repo/test/assert-effect-report.sh"

if [ -n "${JOLT_BIN:-}" ]; then
  jolt_command=("$JOLT_BIN")
else
  test -x "$toolchain"
  jolt_command=("$toolchain" "$JOLT_ASPECT_JOLT")
fi

cleanup() {
  if [ -n "$pid" ]; then
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT INT TERM

mkdir -p "$scenario/target"
(
  cd "$verify_scenario"
  "${jolt_command[@]}" build \
    -m oscope.durable-fault-verify \
    -o "$verify_binary"
)
(
  cd "$scenario"
  "${jolt_command[@]}" build \
    -m oscope.durable-fault-server-main \
    -o target/oscope-durable-fault-server
)

run_case() {
  phase=$1
  root="$tmp/$phase/store"
  scratch="$tmp/$phase/scratch"
  log="$tmp/$phase/server.log"
  response="$tmp/$phase/response"
  mkdir -p "$root" "$scratch"

  env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" \
      OSCOPE_PORT=0 \
      OSCOPE_DURABLE_BACKEND=local \
      OSCOPE_DURABLE_ROOT="$root" \
      OSCOPE_DURABLE_SCRATCH_PARENT="$scratch" \
      OSCOPE_DURABLE_INSTANCE="fault-$phase" \
      OSCOPE_DURABLE_LEASE_TTL_MS=300 \
      OSCOPE_DURABLE_HEARTBEAT_INTERVAL_MS=50 \
      OSCOPE_DURABLE_FAULT_PHASE="$phase" \
      "$server_binary" >"$log" 2>&1 &
  pid=$!

  attempts=0
  port=
  while [ -z "$port" ]; do
    port=$(sed -n 's#.*127\.0\.0\.1:\([0-9][0-9]*\)/oscope.*#\1#p' "$log" | tail -1)
    if ! kill -0 "$pid" 2>/dev/null; then
      cat "$log" >&2
      echo "FAIL: $phase fault server exited before readiness" >&2
      exit 1
    fi
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 300 ]; then
      cat "$log" >&2
      echo "FAIL: $phase fault server readiness timed out" >&2
      exit 1
    fi
    sleep 0.1
  done

  body=$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-fault"}}]},"scopeSpans":[{"scope":{"name":"oscope.durable-fault"},"spans":[{"traceId":"11111111111111111111111111111111","spanId":"2222222222222222","name":"durable.fault.%s","startTimeUnixNano":"1770000000000000000","endTimeUnixNano":"1770000000001000000"}]}]}]}' "$phase")
  status=$(curl -sS -o "$response" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary "$body" "http://127.0.0.1:$port/v1/traces")
  if [ "$status" != 503 ] || [ "$(cat "$response")" != "durability boundary failed" ]; then
    cat "$response" >&2
    cat "$log" >&2
    echo "FAIL: $phase fault returned HTTP $status" >&2
    exit 1
  fi

  # The after fault has committed the head but retained its local WAL. A clean
  # close could flush that WAL again, so terminate before fresh-reader proof.
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  pid=

  env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" "$verify_binary" "$root" "$phase"
}

run_case before
run_case after

(
  cd "$aspect_repo"
  sh test/assert-effect-report.sh "$JOLT_ASPECT_JOLT" "$effects" woven "$report"
)
(
  cd "$repo_root"
  "${jolt_command[@]}" -Srepro \
    -Sdeps '{:paths ["test"]}' \
    -m oscope.durable-fault-report-verify "$report"
)

echo "oscope Durable acknowledgement fault acceptance passed"
