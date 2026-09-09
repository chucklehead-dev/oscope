#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
image=${MINIO_IMAGE:-quay.io/minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e}
container="oscope-crash-minio-$$"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

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

env OSCOPE_DURABLE_CRASH_BACKEND=s3 \
    OSCOPE_DURABLE_CHECKPOINT_EVERY_BATCHES=1 \
    OSCOPE_DURABLE_S3_ENDPOINT="$endpoint" \
    OSCOPE_DURABLE_S3_BUCKET=oscope-durable-crash \
    OSCOPE_DURABLE_S3_PREFIX=process-crash \
    OSCOPE_DURABLE_S3_REGION=us-east-1 \
    OSCOPE_DURABLE_S3_ACCESS_KEY=MINIOACCESS \
    OSCOPE_DURABLE_S3_SECRET_KEY=MINIOSECRET \
    "$repo_root/test/durable_crash_reopen.sh"
