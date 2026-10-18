#!/usr/bin/env bash
# Test-only, fake credentials. No AWS access or production data.
set -euo pipefail
umask 077
task_writer=${1:?qualified writer binary required}
task_reader=${2:?independent reader binary required}
task_fixture=${3:?pinned chDB fixture checkout required}
task_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
for task_file in test/support/s3_tls_fixture.py test/support/s3_http_fixture.py scripts/typed_storage_confirmation_profile.clj scripts/typed_storage_confirmation_receipt.clj; do
  git -C "$task_fixture" show "3d766fa295b0e98c30f275d0a334db92e6dd998c:$task_file" | cmp - "$task_fixture/$task_file"
done
task_root=$(mktemp -d /tmp/oscope-checksum-app.XXXXXXXX)
task_server=
task_success=false
cleanup() {
  if [[ -n "$task_server" ]]; then
    kill "$task_server" 2>/dev/null || true
    wait "$task_server" 2>/dev/null || true
  fi
  if [[ "$task_success" == true && "$task_root" =~ ^/tmp/oscope-checksum-app\.[A-Za-z0-9]+$ ]]; then
    rm -r -- "$task_root"
  else
    echo "retained synthetic checksum fixture: $task_root" >&2
  fi
}
trap cleanup EXIT
run_phase() {
  local task_bin=$1 task_phase=$2 task_log=$3
  local -a task_command=("$task_bin" -Srepro -Sdeps "{:paths [\"src\" \"test\" \"$task_fixture/scripts\"]}" -m oscope.s3-checksum-native-test-runner "$task_phase" "$task_endpoint" "$task_case/cert.pem" "$task_case")
  if [[ -n "${JOLT_WRAPPER:-}" ]]; then task_command=("$JOLT_WRAPPER" "${task_command[@]}"); fi
  (cd "$task_repo" && TMPDIR="$task_case" timeout --signal=TERM --kill-after=5s 120s "${task_command[@]}") > "$task_log" 2>&1
}
for task_mode in qualified drop-option; do
  task_case="$task_root/$task_mode"
  mkdir "$task_case"
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=127.0.0.1 \
    -addext subjectAltName=IP:127.0.0.1 -keyout "$task_case/key.pem" \
    -out "$task_case/cert.pem" > "$task_case/cert.log" 2>&1
  (cd "$task_fixture" && exec python3 test/support/s3_tls_fixture.py "$task_case/port" "$task_case/cert.pem" "$task_case/key.pem") > "$task_case/server.log" 2>&1 &
  task_server=$!
  for _ in $(seq 1 100); do [[ -s "$task_case/port" ]] && break; kill -0 "$task_server"; sleep 0.05; done
  test -s "$task_case/port"
  task_endpoint="https://127.0.0.1:$(<"$task_case/port")"
  if [[ "$task_mode" == qualified ]]; then
    run_phase "$task_writer" writer "$task_case/writer.log"
    grep -Fxq ':s3-checksum-app-observed 5 0 10' "$task_case/writer.log"
    grep -Fxq ':s3-checksum-app-qualified writer' "$task_case/writer.log"
  else
    set +e
    run_phase "$task_writer" writer-drop-option "$task_case/writer.log"
    task_status=$?
    set -e
    test "$task_status" = 1
    grep -Fxq ':s3-checksum-app-observed 5 5 0' "$task_case/writer.log"
    grep -Fxq ':s3-checksum-app-selection-rejected' "$task_case/writer.log"
    grep -Eq '^:s3-checksum-app-result 1 [1-9][0-9]* 0 1$' "$task_case/writer.log"
    ! grep -Fq ':s3-checksum-app-qualified' "$task_case/writer.log"
  fi
  # Even the deliberately unselected writer must leave valid recoverable data.
  run_phase "$task_reader" reader "$task_case/reader.log"
  grep -Fxq ':s3-checksum-app-qualified reader' "$task_case/reader.log"
  grep -E '^:s3-checksum-app-(observed|result) ' "$task_case/writer.log" "$task_case/reader.log" | sed "s|$task_case/||"
  kill "$task_server"
  wait "$task_server" 2>/dev/null || true
  task_server=
  printf 'checksum application %s: writer selection and independent reader checked\n' "$task_mode"
done
task_success=true
