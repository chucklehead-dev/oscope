#!/usr/bin/env bash
# Historical, loopback-only synthetic fixture; not a production MinIO image.
set -euo pipefail
source_dir=$(realpath -e "${1:?provide the exact MinIO source checkout}")
expected_source=07c3a429bfed433e49018cb0f78a52145d4bedeb
test "$(git -C "$source_dir" rev-parse HEAD)" = "$expected_source"
test -z "$(git -C "$source_dir" status --porcelain --untracked-files=all)"
test "$(go version | awk '{print $3}')" = go1.24.6
repo_root=$(cd "$(dirname "$0")/.." && pwd)
build_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/oscope-minio-build-XXXXXXXX")
trap 'test -n "$build_dir" && rm -f "$build_dir/minio"; rmdir "$build_dir/tmp" "$build_dir"' EXIT
mkdir "$build_dir/tmp"
(
  cd "$source_dir"
  GOTOOLCHAIN=local CGO_ENABLED=0 GOMAXPROCS=2 \
    timeout --kill-after=5s 600s go build -p 2 -trimpath -buildvcs=true \
      -mod=readonly -o "$build_dir/minio" .
)
test "$(stat -c %s "$build_dir/minio")" -le 268435456
metadata=$(go version -m "$build_dir/minio")
grep -Fq "vcs.revision=$expected_source" <<< "$metadata"
grep -Fq 'vcs.modified=false' <<< "$metadata"
grep -Fq 'CGO_ENABLED=0' <<< "$metadata"
test "$(awk '/vcs.revision=/{n++} END{print n+0}' <<< "$metadata")" = 1
test "$(awk '/vcs.modified=/{n++} END{print n+0}' <<< "$metadata")" = 1
test -z "$(git -C "$source_dir" status --porcelain --untracked-files=all)"
image=$(timeout --kill-after=5s 120s docker build --quiet \
  -f "$repo_root/test/fixtures/minio-source/Dockerfile" "$build_dir")
[[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
test "$(docker image inspect --format '{{.Id}}' "$image")" = "$image"
printf 'MINIO_IMAGE=%s\nMINIO_SOURCE_SHA=%s\n' "$image" "$expected_source" \
  >> "${GITHUB_ENV:?this builder requires a CI environment file}"
printf 'MinIO fixture built from %s as %s\n' "$expected_source" "$image"
