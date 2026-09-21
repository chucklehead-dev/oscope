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

local_chdb_root() {
  if [ -z "${JOLT_CHDB_ROOT:-}" ]; then
    return 1
  fi
  case "$JOLT_CHDB_ROOT" in
    /*) ;;
    *) echo "JOLT_CHDB_ROOT must be an absolute path" >&2; return 2 ;;
  esac
  if [ ! -d "$JOLT_CHDB_ROOT" ] || [ -L "$JOLT_CHDB_ROOT" ]; then
    echo "JOLT_CHDB_ROOT must name a non-symlink existing directory" >&2
    return 2
  fi
  root=$(realpath "$JOLT_CHDB_ROOT")
  if [ "$root" != "$JOLT_CHDB_ROOT" ] || [ ! -f "$root/deps.edn" ] || \
     [ ! -f "$root/src/jdbc/chdb/durable.clj" ] || [ ! -d "$root/resources" ]; then
    echo "JOLT_CHDB_ROOT is not a usable jolt-chdb checkout" >&2
    return 2
  fi
  printf '%s\n' "$root"
}

# The private scenario is an EDN source file. Keep file-system paths data: a
# quote or backslash is legal in a POSIX path and must not become EDN syntax;
# control characters make an auditable scenario impossible, so reject them.
edn_string() {
  local value=$1 LC_ALL=C
  if [[ "$value" == *[[:cntrl:]]* ]]; then
    echo "local checkout paths must not contain control characters" >&2
    return 2
  fi
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '"%s"' "$value"
}

local_otel_clickhouse_root() {
  local candidate main_worktree root
  if [ -n "${JOLT_OTEL_CLICKHOUSE_ROOT:-}" ]; then
    candidate=$JOLT_OTEL_CLICKHOUSE_ROOT
  else
    # repo_root can itself be a linked worktree. Resolve the main Oscope
    # worktree first, then select the sibling exporter checkout from its
    # containing workspace rather than assuming repo_root/.. is that root.
    main_worktree=$(git -C "$repo_root" worktree list --porcelain 2>/dev/null |
      sed -n '/^worktree /{s/^worktree //;p;q;}')
    if [ -z "$main_worktree" ]; then
      echo "could not derive JOLT_OTEL_CLICKHOUSE_ROOT from the Oscope worktree" >&2
      return 2
    fi
    candidate=$(dirname -- "$main_worktree")/jolt-otel-clickhouse
  fi
  case "$candidate" in
    /*) ;;
    *) echo "JOLT_OTEL_CLICKHOUSE_ROOT must be an absolute path" >&2; return 2 ;;
  esac
  if [ ! -d "$candidate" ] || [ -L "$candidate" ]; then
    echo "JOLT_OTEL_CLICKHOUSE_ROOT must name a non-symlink existing directory" >&2
    return 2
  fi
  root=$(realpath "$candidate")
  if [ "$root" != "$candidate" ] || [ ! -f "$root/deps.edn" ] || \
     [ ! -f "$root/src/otel/exporter/chdb.clj" ]; then
    echo "JOLT_OTEL_CLICKHOUSE_ROOT is not a usable jolt-otel-clickhouse checkout" >&2
    return 2
  fi
  printf '%s\n' "$root"
}

write_isolated_deps() {
  local root exporter_root test_path oscope_path chdb_path exporter_path resources_path
  root=$(local_chdb_root) || return $?
  exporter_root=$(local_otel_clickhouse_root) || return $?
  test_path=$(edn_string "$repo_root/test") || return $?
  oscope_path=$(edn_string "$repo_root") || return $?
  chdb_path=$(edn_string "$root") || return $?
  exporter_path=$(edn_string "$exporter_root") || return $?
  resources_path=$(edn_string "$root/resources") || return $?
  printf '{:paths [%s]\n :deps {io.github.chucklehead-dev/oscope {:local/root %s}\n        io.github.chucklehead-dev/jolt-chdb {:local/root %s}\n        io.github.chucklehead-dev/jolt-otel-clickhouse {:local/root %s}}\n :jolt/build {:embed [%s]}}\n' \
    "$test_path" "$oscope_path" "$chdb_path" "$exporter_path" "$resources_path"
}

cleanup() {
  status=$?
  if [ -n "$pid" ]; then
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  if [ "$status" -eq 0 ]; then
    rm -rf -- "$tmp"
  else
    # The directory is mktemp-private. Retain it only for failed acceptance
    # attempts so the operator can inspect build/server logs locally. The
    # marker deliberately contains only a local path, never request payloads
    # or environment values (which can contain object-store credentials).
    printf 'RETAINED: Durable crash evidence directory: %s\n' "$tmp" >&2
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "${1:-}" = "--check-jolt-chdb-root" ]; then
  if [ -z "${JOLT_CHDB_ROOT:-}" ]; then
    printf 'default-relative=../../../jolt-chdb\n'
  else
    root=$(local_chdb_root) || exit $?
    printf 'override=%s\n' "$root"
  fi
  exit 0
fi

if [ "${1:-}" = "--check-local-roots" ]; then
  root=$(local_chdb_root) || exit $?
  exporter_root=$(local_otel_clickhouse_root) || exit $?
  printf 'chdb=%s\nexporter=%s\n' "$root" "$exporter_root"
  exit 0
fi

if [ "${1:-}" = "--print-isolated-deps" ]; then
  write_isolated_deps
  exit $?
fi

: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"

if [ -n "${JOLT_WRAPPER:-}" ]; then
  jolt_command=("$JOLT_WRAPPER" jolt)
elif [ -n "${JOLT_BIN:-}" ]; then
  jolt_command=("$JOLT_BIN")
else
  jolt_command=("$toolchain" jolt)
fi

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

# The normal developer and CI layouts retain durable-process's relative local
# root. An explicitly requested absolute override is solely for an isolated
# Oscope worktree: it builds from a private, complete EDN scenario with the
# explicitly validated chDB checkout and a validated exporter checkout. It
# never edits the checked-in scenario or broadens CI.
if [ -n "${JOLT_CHDB_ROOT:-}" ]; then
  isolated_scenario="$tmp/durable-process"
  mkdir -p "$isolated_scenario"
  # Write the complete scenario rather than deleting and rewriting forms in
  # the checked-in fixture. The isolated root explicitly controls only the
  # three local repositories needed by this integration gate.
  write_isolated_deps >"$isolated_scenario/deps.edn" || exit $?
  scenario="$isolated_scenario"
fi
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
  "OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES=${OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES:-1000}"
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

post_signal() {
  path=$1
  body=$2
  status=$(curl -sS -o "$tmp/response" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary "$body" "http://127.0.0.1:$port$path")
  if [ "$status" != 200 ]; then
    cat "$tmp/response" >&2
    cat "$server_log" >&2
    echo "FAIL: Durable ingest returned HTTP $status" >&2
    exit 1
  fi
}

post_fixture_signals() {
  trace_id=$1
  stage=$2
  trace_body=$(printf '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-crash"}}]},"scopeSpans":[{"scope":{"name":"oscope.durable-crash"},"spans":[{"traceId":"%s","spanId":"2222222222222222","name":"durable.crash.%s","startTimeUnixNano":"1770000000000000000","endTimeUnixNano":"1770000000001000000"}]}]}]}' "$trace_id" "$stage")
  log_body=$(printf '{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-crash"}}]},"scopeLogs":[{"scope":{"name":"oscope.durable-crash"},"logRecords":[{"timeUnixNano":"1770000000001000000","observedTimeUnixNano":"1770000000001000001","severityNumber":9,"severityText":"INFO","body":{"stringValue":"durable.crash.%s.log"},"traceId":"%s","spanId":"2222222222222222"}]}]}]}' "$stage" "$trace_id")
  metric_body=$(printf '{"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"oscope-durable-crash"}}]},"scopeMetrics":[{"scope":{"name":"oscope.durable-crash"},"metrics":[{"name":"durable.crash.%s.gauge","unit":"1","gauge":{"dataPoints":[{"timeUnixNano":"1770000000001000000","asInt":"1"}]}},{"name":"durable.crash.%s.sum","unit":"1","sum":{"aggregationTemporality":2,"isMonotonic":true,"dataPoints":[{"startTimeUnixNano":"1770000000000000000","timeUnixNano":"1770000000001000000","asInt":"4"}]}},{"name":"durable.crash.%s.histogram","unit":"ms","histogram":{"aggregationTemporality":1,"dataPoints":[{"startTimeUnixNano":"1770000000000000000","timeUnixNano":"1770000000001000000","count":"2","sum":7.0,"bucketCounts":["1","1"],"explicitBounds":[5.0]}]}}]}]}]}' "$stage" "$stage" "$stage")
  post_signal /v1/traces "$trace_body"
  post_signal /v1/logs "$log_body"
  post_signal /v1/metrics "$metric_body"
}

crash_server() {
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  pid=
  # The next process must take over through ordinary lease expiry, not force.
  sleep "$takeover_wait_seconds"
}

start_server first "$tmp/first.log"
post_fixture_signals 11111111111111111111111111111111 first
crash_server

start_server second "$tmp/second.log"
post_fixture_signals 33333333333333333333333333333333 second
crash_server

if [ "$backend" = s3 ]; then
  env "${durable_env[@]}" "$verify_binary" --verify-s3 2
else
  env "${durable_env[@]}" "$verify_binary" --verify-local "$tmp/store" 2
fi
