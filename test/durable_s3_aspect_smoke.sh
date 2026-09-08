#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
workspace=$(CDPATH= cd -- "$repo_root/.." && pwd)
toolchain="$workspace/tools/jolt-with-chez-10.4.1"
aspect_repo="$workspace/jolt-aspect-packs"
scenario="$repo_root/test/durable-aspect"
binary="$scenario/target/oscope-durable-s3-aspect-test"
report="$scenario/target/aspects.edn"
effects="$scenario/target/oscope-durable-s3-aspect-test.build/effects.edn"
image=${MINIO_IMAGE:-quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e}
container="oscope-aspect-minio-$$"

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

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

(
  cd "$scenario"
  "$toolchain" "$JOLT_ASPECT_JOLT" build \
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
  cd "$aspect_repo"
  sh test/assert-effect-report.sh "$JOLT_ASPECT_JOLT" "$effects" woven "$report"
  "$toolchain" "$JOLT_ASPECT_JOLT" -Srepro \
    -Sdeps '{:paths ["test" "src"]}' \
    -m jolt.aspect-packs.chdb-durable.report-test "$report"
)

echo "oscope Durable S3 aspect smoke passed"
