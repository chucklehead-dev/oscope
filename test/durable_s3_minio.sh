#!/usr/bin/env bash
set -euo pipefail

: "${JOLT_CHDB_LIB:?JOLT_CHDB_LIB must name the qualified libchdb shared library}"
runner=${JOLT_BIN:-jolt}
test_alias=${OSCOPE_DURABLE_S3_TEST_ALIAS:-test-durable-s3-dev}
image=${MINIO_IMAGE:-quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e}
container="oscope-minio-$$"

case "$test_alias" in
  test-durable-s3|test-durable-s3-dev) ;;
  *) echo "unsupported Durable S3 test alias: $test_alias" >&2; exit 2 ;;
esac

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

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

env OSCOPE_TEST_S3_ENDPOINT="$endpoint" \
  "$runner" "-M:$test_alias"
