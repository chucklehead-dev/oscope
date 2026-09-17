#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain="$workspace/tools/jolt-with-chez-10.4.1"
build_alias=${OSCOPE_DURABLE_ASPECT_BUILD_ALIAS:-dev}

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
test -f "$JOLT_BIN" && test -x "$JOLT_BIN" && test ! -L "$JOLT_BIN"
test "$(realpath "$JOLT_BIN")" = "$JOLT_BIN"
[[ "$QUALIFIED_RUNTIME_BINARY_SHA256" =~ ^[0-9a-f]{64}$ ]]
test "$(sha256sum "$JOLT_BIN" | cut -d ' ' -f1)" = "$QUALIFIED_RUNTIME_BINARY_SHA256"
export JOLT_BIN

if [ "${GITHUB_ACTIONS:-}" = true ]; then
  # Hosted compiler provisioning above already pins Chez; never use JOLT_BIN
  # here, because it is reserved for the root-graph recovery reader.
  jolt_command=("$JOLT_ASPECT_JOLT")
else
  test -x "$toolchain"
  jolt_command=("$toolchain" "$JOLT_ASPECT_JOLT")
fi

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

(
  cd "$scenario"
  "${jolt_command[@]}" build \
    -m oscope.durable-s3-aspect-test-runner \
    -o target/oscope-durable-s3-aspect-test
)

docker run --rm -d \
  --name "$container" \
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

env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" \
  OSCOPE_TEST_S3_ENDPOINT="$endpoint" \
  "$binary"

(
  cd "$repo_root"
  sh "$aspect_repo/test/assert-effect-report.sh" \
    "$JOLT_ASPECT_JOLT" "$effects" woven "$report"
  "${jolt_command[@]}" -Srepro \
    -Sdeps '{:paths ["test"]}' \
    -m oscope.durable-aspect-report-test "$report"
)

echo "oscope Durable S3 aspect smoke passed"
