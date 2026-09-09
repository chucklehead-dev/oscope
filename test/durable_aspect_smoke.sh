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
binary="$scenario/target/oscope-durable-aspect-test"
report="$scenario/target/aspects.edn"
effects="$scenario/target/oscope-durable-aspect-test.build/effects.edn"

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

(
  cd "$scenario"
  "${jolt_command[@]}" build \
    -m oscope.durable-aspect-test-runner \
    -o target/oscope-durable-aspect-test
  env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" "$binary"
)

(
  cd "$aspect_repo"
  sh test/assert-effect-report.sh "$JOLT_ASPECT_JOLT" "$effects" woven "$report"
)

(
  cd "$repo_root"
  "${jolt_command[@]}" -Srepro \
    -Sdeps '{:paths ["test"]}' \
    -m oscope.durable-aspect-report-test "$report"
)

echo "oscope Durable aspect smoke passed"
