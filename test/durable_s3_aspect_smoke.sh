#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain=${OSCOPE_JOLT_TOOLCHAIN:-"$workspace/tools/jolt-with-chez-10.4.1"}
build_alias=${OSCOPE_DURABLE_ASPECT_BUILD_ALIAS:-dev}
# A fixed manual qualification control, never a normal push/PR behavior.
evidence_control=${OSCOPE_DURABLE_EVIDENCE_CONTROL:-none}
case "$evidence_control" in
  none) ;;
  post-qualified-harness-rejection)
    test "${GITHUB_ACTIONS:-}" = true &&
      test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch || exit 2 ;;
  *) echo 'unsupported synthetic evidence control' >&2; exit 2 ;;
esac

case "$build_alias" in
  dev)
    aspect_repo="$workspace/jolt-aspect-packs"
    scenario="$repo_root/test/durable-aspect"
    ;;
  published)
    aspect_repo="$repo_root/.qualification/jolt-aspect-packs"
    scenario="$repo_root/test/durable-aspect-published"
    ;;
  *) echo "unsupported Durable aspect build alias: $build_alias" >&2; exit 2 ;;
esac
binary="$scenario/target/oscope-durable-s3-aspect-test"
report="$scenario/target/aspects.edn"
effects="$scenario/target/oscope-durable-s3-aspect-test.build/effects.edn"
image=${MINIO_IMAGE:-quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e}
container="oscope-aspect-minio-$$"

: "${JOLT_ASPECT_JOLT:?JOLT_ASPECT_JOLT must name the aspect-capable jolt executable}"
: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"
: "${JOLT_BIN:?JOLT_BIN must name the authenticated ordinary reader runtime}"
: "${QUALIFIED_RUNTIME_BINARY_SHA256:?qualified reader binary digest required}"

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

# Do not let the compiler override leak into the independent native reader.
# Producer/manifest authentication is owned by the preceding workflow gate;
# recheck the exact selected reader before compiling or starting the fixture.
case "$JOLT_BIN" in
  /*) ;;
  *) echo "JOLT_BIN must be an absolute qualified reader path" >&2; exit 2 ;;
esac
test -f "$JOLT_BIN" && test -x "$JOLT_BIN" && test ! -L "$JOLT_BIN" || exit 2
test "$(realpath "$JOLT_BIN")" = "$JOLT_BIN"
[[ "$QUALIFIED_RUNTIME_BINARY_SHA256" =~ ^[0-9a-f]{64}$ ]]
test "$(sha256sum "$JOLT_BIN" | cut -d ' ' -f1)" = "$QUALIFIED_RUNTIME_BINARY_SHA256"
export JOLT_BIN

if [ "${GITHUB_ACTIONS:-}" = true ]; then
  # Hosted compiler provisioning above already pins Chez; never use JOLT_BIN
  # here, because it is reserved for the root-graph recovery reader.
  jolt_command=("$JOLT_ASPECT_JOLT")
else
  # Isolated worktrees may supply the same pinned workspace wrapper explicitly.
  # Reject relative paths, aliases and a differently named interpreter wrapper.
  case "$toolchain" in
    /*/tools/jolt-with-chez-10.4.1) ;;
    *) echo 'local Jolt toolchain must be an absolute pinned wrapper path' >&2; exit 2 ;;
  esac
  test -x "$toolchain"
  test ! -L "$toolchain" && test "$(realpath -e "$toolchain")" = "$toolchain" || exit 2
  jolt_command=("$toolchain" "$JOLT_ASPECT_JOLT")
fi

# This root belongs ONLY to the fixed synthetic MinIO fixture. No real AWS,
# Langfuse, arbitrary environment dump or broad workspace log enters a bundle.
evidence_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/oscope-durable-evidence-XXXXXXXX")
test "$(realpath "$evidence_root")" = "$evidence_root"
mkdir "$evidence_root/backend" "$evidence_root/reports"
[[ "$image" =~ ^[A-Za-z0-9./:-]+@sha256:[0-9a-f]{64}$ ]]
printf 'oscope-synthetic-s3-v1\n' > "$evidence_root/scope"
if [ "${GITHUB_ACTIONS:-}" = true ]; then
  printf 'OSCOPE_DURABLE_FAILED_EVIDENCE=%s\n' "$evidence_root" >> "$GITHUB_ENV"
fi
started=0
retired=0
semantic_pass=0

reader_settled() {
  local receipt="$evidence_root/reader/settlement.receipt"
  test -f "$receipt" && test ! -L "$receipt" &&
    test "$(wc -c < "$receipt")" -le 128 &&
    test "$(wc -l < "$receipt")" -eq 1 &&
    grep -Fxq '1 2 0 20 0 0 1 1 1 1' "$receipt"
}

retire_minio() {
  if [ "$started" -eq 0 ]; then retired=1; return 0; fi
  # Owned service only: stopping it freezes the retained backend, but is NOT
  # evidence that a missing reader final receipt was settled.
  timeout --kill-after=5s 10s docker stop --time 5 "$container" >/dev/null 2>&1 || return 1
  test "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" = false || return 1
  retired=1
}

archive_failure() {
  local bytes unexpected snapshot="$evidence_root/snapshot"
  test "$retired" -eq 1 || return 1
  # Stop/freeze confirmation precedes the snapshot; reject symlinks instead of
  # following unexpected filesystem targets or silently altering replay bytes.
  unexpected=$(find "$evidence_root" -type l -print -quit) || return 1
  test -z "$unexpected" || return 1
  # cp -a can break an external hardlink into an apparently regular private
  # copy. Reject linked source bytes BEFORE copying, not just in the snapshot.
  unexpected=$(find "$evidence_root" -type f -links +1 -print -quit) || return 1
  test -z "$unexpected" || return 1
  bytes=$(du -sb "$evidence_root" | cut -f1) || return 1
  test "$bytes" -le 268435456 || return 1
  if [ -e "$evidence_root/control.status" ]; then
    control_status_valid || return 1
    test "$1" = 77 || return 1
  fi
  printf 'schema=1\nscope=synthetic-s3\nqualification=failed\noriginal-exit=%s\nreader-settlement=%s\nbackend-snapshot=unqualified\nreader-log-snapshot=possibly-unfinished\n' \
    "$1" "$(if reader_settled; then printf confirmed; else printf unknown; fi)" > "$evidence_root/outcome" || return 1
  local source_head source_tree
  source_head=$(git -C "$repo_root" rev-parse HEAD) || return 1
  source_tree=$(git -C "$repo_root" rev-parse 'HEAD^{tree}') || return 1
  [[ "$source_head" =~ ^[0-9a-f]{40}$ && "$source_tree" =~ ^[0-9a-f]{40}$ ]] || return 1
  printf 'runtime-sha256=%s\nminio-image=%s\nsource-head=%s\nsource-tree=%s\nsource-worktree=%s\n' \
    "$QUALIFIED_RUNTIME_BINARY_SHA256" "$image" "$source_head" "$source_tree" \
    "$(if git -C "$repo_root" diff-index --quiet HEAD --; then printf clean; else printf dirty; fi)" > "$evidence_root/provenance" || return 1
  # Freeze a COPY of allowlisted evidence before checksums. An unknown reader
  # may still append its original logs; this preserves an explicitly unfinished
  # prefix, not a fabricated final receipt or universal quiescence claim.
  mkdir "$snapshot" || return 1
  cp -a -- "$evidence_root/backend" "$evidence_root/reports" "$snapshot/" || return 1
  for file in scope outcome provenance cleanup.status control.status fixture.stdout fixture.stderr reader; do
    if [ -e "$evidence_root/$file" ]; then
      cp -a -- "$evidence_root/$file" "$snapshot/" || return 1
    fi
  done
  unexpected=$(find "$snapshot" ! -type d ! -type f -print -quit) || return 1
  test -z "$unexpected" || return 1
  unexpected=$(find "$snapshot" -type f -links +1 -print -quit) || return 1
  test -z "$unexpected" || return 1
  (
    cd "$snapshot" || exit 1
    find backend reports -type f -print0 || exit 1
    for file in scope outcome provenance cleanup.status control.status fixture.stdout fixture.stderr; do
      if [ -f "$file" ]; then printf '%s\0' "$file"; fi
    done
    if [ -d reader ]; then find reader -type f -print0 || exit 1; fi
  ) | (cd "$snapshot" || exit 1; sort -z | xargs -0 -r sha256sum > checksums.sha256) || return 1
  timeout --kill-after=5s 30s tar -C "$snapshot" -czf "$evidence_root/replay.tar.gz" -- . || return 1
  test "$(wc -c < "$evidence_root/replay.tar.gz")" -le 268435456 || return 1
  (cd "$evidence_root" || exit 1; sha256sum replay.tar.gz > replay.sha256) || return 1
}

control_status_valid() {
  local status="$evidence_root/control.status" field
  test -f "$status" && test ! -L "$status" || return 1
  test "$(wc -c < "$status")" -le 128 && test "$(wc -l < "$status")" = 4 || return 1
  for field in schema=1 control=post-qualified-harness-rejection qualifying-exit=0 rejection-exit=77; do
    test "$(grep -Fxc "$field" "$status")" = 1 || return 1
  done
}

reject_qualified_control() {
  if [ "$evidence_control" = none ]; then return 0; fi
  test "$evidence_control" = post-qualified-harness-rejection &&
    test "${GITHUB_ACTIONS:-}" = true &&
    test "${GITHUB_EVENT_NAME:-}" = workflow_dispatch &&
    test "$semantic_pass" = 1 && reader_settled || return 2
  # Call only after ALL fixture, reader and report oracles pass. This is a
  # deliberate harness rejection, NOT native corruption or unsettled ownership.
  test ! -e "$evidence_root/control.status" && test ! -L "$evidence_root/control.status" || return 2
  (set -o noclobber
   printf 'schema=1\ncontrol=post-qualified-harness-rejection\nqualifying-exit=0\nrejection-exit=77\n' > "$evidence_root/control.status") || return 2
  control_status_valid || return 2
  echo 'synthetic post-qualified harness rejection: qualifying exit 0, gate exit 77'
  return 77
}

capture_cleanup_status() {
  local root="$1" code="$2" phase="$3"
  [[ "$code" =~ ^[0-9]{1,3}$ ]] && test "$code" -gt 0 && test "$code" -le 255 || return 1
  case "$phase" in minio-remove|fixture-delete|path-guard) ;; *) return 1 ;; esac
  printf 'schema=1\ncapture=incomplete\noriginal-exit=%s\n' "$code" > "$root/capture.status" || return 1
  printf 'schema=1\nsource-qualification=semantic-and-reader-confirmed\ncleanup=failed\nphase=%s\nqualifying-exit=0\ncleanup-exit=%s\n' \
    "$phase" "$code" > "$root/cleanup.status" || return 1
  if [ "${GITHUB_ACTIONS:-}" = true ]; then
    printf 'OSCOPE_DURABLE_FAILED_EVIDENCE=%s\n' "$root" >> "$GITHUB_ENV" || return 1
  fi
}

cleanup() {
  local original_exit=$?
  trap - EXIT
  if ! retire_minio; then
    printf 'schema=1\ncapture=incomplete\noriginal-exit=%s\n' "$original_exit" > "$evidence_root/capture.status"
    printf 'FAIL: owned MinIO retirement unconfirmed; evidence retained, snapshot unavailable\n' >&2
    printf 'oscope-durable-evidence=%s\n' "$evidence_root"
    exit 1
  fi
  if [ "$original_exit" -eq 0 ] && [ "$semantic_pass" -eq 1 ] && reader_settled; then
    if docker rm "$container" >/dev/null 2>&1; then
      # Deletion may partially succeed before reporting an error. Allocate an
      # independent tiny status root first; NEVER promise replay after deletion.
      local fallback
      fallback=$(mktemp -d "$(dirname "$evidence_root")/oscope-durable-evidence-XXXXXXXX") || exit 1
      test "$(realpath "$fallback")" = "$fallback" || exit 1
      printf 'oscope-synthetic-s3-v1\n' > "$fallback/scope" || exit 1
      printf 'oscope-durable-cleanup-status=%s\n' "$fallback"
      if ! test "$(realpath "$evidence_root")" = "$evidence_root" ||
         ! test "$(cat "$evidence_root/scope")" = oscope-synthetic-s3-v1; then
        capture_cleanup_status "$fallback" 1 path-guard || exit 1
        printf 'FAIL: owned fixture cleanup guard rejected; no replay claim\n' >&2
        exit 1
      fi
      if timeout --kill-after=5s 10s rm -rf -- "$evidence_root"; then
        if test ! -e "$evidence_root"; then exit 0; fi
        local delete_exit=1
      else
        local delete_exit=$?
      fi
      capture_cleanup_status "$fallback" "$delete_exit" fixture-delete || exit 1
      printf 'FAIL: owned fixture deletion incomplete; status only, no replay claim\n' >&2
      exit 1
    else
      local cleanup_exit=$?
      capture_cleanup_status "$evidence_root" "$cleanup_exit" minio-remove || exit 1
      # No filesystem deletion attempted: the exact backend/seal/logs remain
      # available for the normal failure archive, despite prior qualification.
      original_exit=$cleanup_exit
      printf 'FAIL: owned MinIO removal incomplete; capturing retained fixture\n' >&2
    fi
  fi
  if ! archive_failure "$original_exit"; then
    printf 'schema=1\ncapture=incomplete\noriginal-exit=%s\n' "$original_exit" > "$evidence_root/capture.status"
    printf 'FAIL: synthetic replay artifact capture incomplete\n' >&2
  else
    printf 'schema=1\ncapture=complete\noriginal-exit=%s\n' "$original_exit" > "$evidence_root/capture.status"
    printf 'PASS: synthetic failed replay bundle captured (not recovery qualification)\n'
  fi
  printf 'oscope-durable-evidence=%s\n' "$evidence_root"
  if [ "$original_exit" = 77 ] && control_status_valid; then exit 77; fi
  exit 1
}
trap cleanup EXIT

(
  cd "$scenario"
  "${jolt_command[@]}" build \
    -m oscope.durable-s3-aspect-test-runner \
    -o target/oscope-durable-s3-aspect-test
)

test -f "$report" && test ! -L "$report" || exit 2
test -f "$effects" && test ! -L "$effects" || exit 2
cp -- "$report" "$evidence_root/reports/aspects.edn"
cp -- "$effects" "$evidence_root/reports/effects.edn"
cp -- "$repo_root/deps.edn" "$evidence_root/reports/deps.edn"
started=1
docker run -d \
  --name "$container" \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,src=$evidence_root/backend,dst=/data" \
  -p 127.0.0.1::9000 \
  -e MINIO_ROOT_USER=MINIOACCESS \
  -e MINIO_ROOT_PASSWORD=MINIOSECRET \
  "$image" server /data >/dev/null
published=$(docker port "$container" 9000/tcp)
port=${published##*:}
endpoint="http://127.0.0.1:$port"
for _ in $(seq 1 120); do
  if curl -fsS "$endpoint/minio/health/live" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
curl -fsS "$endpoint/minio/health/live" >/dev/null

# Raw fixture output stays private in the synthetic-only archive. Public logs
# contain only exact closed result markers, never arbitrary exception content.
env -i HOME="$HOME" PATH="$PATH" LANG=C.UTF-8 \
  JOLT_BIN="$JOLT_BIN" JOLT_CHDB_LIB="$JOLT_CHDB_LIB" \
  JOLT_CACHE_DIR="${JOLT_CACHE_DIR:-$evidence_root/cache}" \
  JOLT_GITLIBS_DIR="${JOLT_GITLIBS_DIR:-$HOME/.jolt/gitlibs}" \
  OSCOPE_DURABLE_FAILURE_EVIDENCE_ROOT="$evidence_root" \
  OSCOPE_TEST_S3_ENDPOINT="$endpoint" \
  "$binary" > "$evidence_root/fixture.stdout" 2> "$evidence_root/fixture.stderr"
grep -E '^Ran [0-9]+ tests\. [0-9]+ assertions passed, 0 failures, 0 errors\.$|^oscope Durable S3 woven history and telemetry validated [0-9]+ commands [0-9]+ spans$|^:durable-native-child reader 2 :exit 0 :terminal true :settled true :valid true$' \
  "$evidence_root/fixture.stdout" || true

(
  cd "$repo_root"
  sh "$aspect_repo/test/assert-effect-report.sh" \
    "$JOLT_ASPECT_JOLT" "$effects" woven "$report"
  "${jolt_command[@]}" -Srepro \
    -Sdeps '{:paths ["test"]}' \
    -m oscope.durable-aspect-report-test "$report"
)

reader_settled
semantic_pass=1
reject_qualified_control
echo "oscope Durable S3 aspect smoke passed"
