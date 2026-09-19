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

# Writer children execute the freshly built SAME woven image, not ordinary
# Jolt. Recovery readers use the separately authenticated positive artifact.
reader_jolt=${OSCOPE_DURABLE_READER_JOLT:-$(command -v jolt)}
case "$reader_jolt" in
  /*) ;;
  *) echo "qualified reader executable must be absolute" >&2; exit 2 ;;
esac
test -f "$reader_jolt" && test -x "$reader_jolt" && test ! -L "$reader_jolt"
test "$(realpath "$reader_jolt")" = "$reader_jolt"
printf '%s  %s\n' \
  31cff7ea89a652bd99848cf15686eb576d90d9ac3962ac07701fb6005e6eb458 \
  "$reader_jolt" | sha256sum -c -

if [ -n "${JOLT_BIN:-}" ]; then
  jolt_command=("$JOLT_BIN")
else
  test -x "$toolchain"
  jolt_command=("$toolchain" "$JOLT_ASPECT_JOLT")
fi

(
  cd "$scenario"
  # Maintained PURE contract gate: true fixture Vars and synthetic process
  # receipts only. This must not spawn a native lifetime or woven writer.
  "${jolt_command[@]}" -Srepro -e '
    (require (quote db.jdbc))
    (require (quote oscope.durable-aspect-child-runner-test))
    (let [n (quote oscope.durable-aspect-child-runner-test)
          expected #{(quote woven-native-inventory-retains-all-three-true-vars)
                     (quote woven-final-receipts-require-real-history-and-nonzero-counts)
                     (quote woven-orchestration-uses-the-same-image-and-stops-unconfirmed-ownership)}
          actual (set (for [[name v] (ns-publics n) :when (:test (meta v))] name))]
      (assert (= expected actual))
      (let [result (clojure.test/run-tests n)]
        ;; run-tests also returns :type :summary; counts are the contract.
        (println :durable-woven-control-summary (select-keys result [:type]))
        (assert (= {:test 3 :pass 34 :fail 0 :error 0}
                   (select-keys result [:test :pass :fail :error])))))'
  "${jolt_command[@]}" build \
    -m oscope.durable-aspect-test-runner \
    -o target/oscope-durable-aspect-test
)

(
  # Native reader commands need the root's maintained :test-durable alias;
  # aliases in a local/root dependency are not inherited by scenario deps.
  cd "$repo_root"
  env JOLT_CHDB_LIB="$JOLT_CHDB_LIB" JOLT_BIN="$reader_jolt" \
    OSCOPE_DURABLE_WOVEN_EXECUTABLE="$binary" \
    timeout --kill-after=5s 210s "$binary"
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
