#!/usr/bin/env bash
set -euo pipefail
task_out=${1:?pass a new absolute evidence directory}
task_sdk=/home/chuck/ai-src/worktrees/otel-default-scalar-options-20261020
test ! -e "$task_out"
task_bin=/home/chuck/.cache/jolt-stock-v0.8.23-codec/jolt-v0.8.23-x86_64-linux/jolt
task_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
export JOLT_CHDB_LIB=/home/chuck/.cache/jolt-chdb/26.9.0/linux-amd64/libchdb.so
export JOLT_AOT_CACHE=0 JOLT_NO_USER_DEPS=1
export BENCH_CLK_TCK
BENCH_CLK_TCK=$(getconf CLK_TCK)
printf '%s  %s\n' 7bf7acae4b4bcb5c27b3651fa4a94d2d4e6a7fc43b5b821def2f8be3d6f00e94 "$task_bin" | sha256sum -c -
printf '%s  %s\n' a7d22ade86e1de82b187adf572b569a6034600ce932c52b0acb8c97b82430557 "$JOLT_CHDB_LIB" | sha256sum -c -
mkdir -p "$task_out"
sha256sum benchmark/oscope/socket_ingest.clj deps.edn src/oscope/server.clj src/oscope/otlp.clj src/oscope/export_options.clj research/socket-ingest-20261020/run-sdk-defaults-abba.sh "$task_sdk/src/otel/any_value.clj" > "$task_out/sources.sha256"
for task_arm in 1-baseline 2-candidate 3-candidate 4-baseline; do
  task_mode=codec-library-reader
  task_deps='{:paths ["src" "benchmark"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "0f51b99101bc5e840f957c073f87b6f877309a25"} io.github.chucklehead-dev/jolt-codec {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git" :git/sha "43291a2fbcc6f1f855103c1db4264a3c13d618c1" :deps/root "incubator/codec"}}}'
  if [[ "$task_arm" == *candidate ]]; then
    task_deps=${task_deps%\}\}}' io.github.casselc/otel {:local/root "'"$task_sdk"'"}}}'
  fi
  task_store=$(mktemp -d /tmp/oscope-socket-pair.XXXXXXXX)
  printf '%s\n' "$task_store" > "$task_out/$task_arm.store-path"
  cp /proc/loadavg "$task_out/$task_arm.load-before.txt"
  timeout --signal=TERM --kill-after=5s 240s "$task_wrapper" "$task_bin" -Srepro \
    -Sdeps "$task_deps" \
    -m oscope.socket-ingest writer "$task_store" "$task_mode" 512 8 > "$task_out/$task_arm.writer.log" 2>&1
  timeout --signal=TERM --kill-after=5s 90s "$task_wrapper" "$task_bin" -Srepro \
    -Sdeps '{:paths ["src" "benchmark"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "0f51b99101bc5e840f957c073f87b6f877309a25"} io.github.chucklehead-dev/jolt-codec {:git/url "https://github.com/chucklehead-dev/jolt-otel-clickhouse.git" :git/sha "43291a2fbcc6f1f855103c1db4264a3c13d618c1" :deps/root "incubator/codec"}}}' -m oscope.socket-ingest reader "$task_store" > "$task_out/$task_arm.reader.log" 2>&1
  cp /proc/loadavg "$task_out/$task_arm.load-after.txt"
  for task_receipt in writer reader seal; do
    cp "$task_store/$task_receipt.edn" "$task_out/$task_arm.$task_receipt.edn"
    cmp "$task_store/$task_receipt.edn" "$task_out/$task_arm.$task_receipt.edn"
  done
  bb -e '(require (quote clojure.edn))
    (let [[w r] (map #(clojure.edn/read-string (slurp %)) *command-line-args*)]
      (assert (= 512 (:batch-size w))) (assert (= 8 (:rounds w)))
      (assert (= 4096 (:confirmed-rows w))) (assert (= 8 (count (:samples-nanos w))))
      (assert (every? pos? (:samples-nanos w)))
      (assert (<= (:wire-bytes w) 1048576))
      (assert (clojure.string/includes? (:any-value-source w)
                (if (clojure.string/includes? (first *command-line-args*) "candidate")
                  "/worktrees/otel-default-scalar-options-20261020/"
                  "26186ce79941e84e0cc67eb148dd8dd43b4e1880")))
      (assert (= 9 (:codec-calls w))) (assert (= 9 (:stream-calls w)))
      (assert (= 8 (count (get-in w [:phase-samples :json-document-read]))))
      (assert (= 0 (:reader-requests w)))
      (assert (= {:accepted (if (= "codec-library-reader" (:mode w)) 9 0) :declined 0} (:library-results w)))
      (assert (clojure.string/includes? (:codec-source w) "43291a2fbcc6f1f855103c1db4264a3c13d618c1"))
      (when (:library-reader? w) (assert (clojure.string/includes? (:library-reader-source w) "43291a2fbcc6f1f855103c1db4264a3c13d618c1")))
      (assert (= 4608 (:rows r))) (assert (= 22 (:core-columns r)))
      (assert (:independent-reader r)))' "$task_out/$task_arm.writer.edn" "$task_out/$task_arm.reader.edn"
  sha256sum -c "$task_out/sources.sha256" > "$task_out/$task_arm.source-after.log"
  du -sk "$task_store" > "$task_out/$task_arm.retired-kib.txt"
  case "$task_store" in /tmp/oscope-socket-pair.????????) ;; *) exit 91;; esac
  test ! -L "$task_store"
  test "$(realpath "$task_store")" = "$task_store"
  # Both exact processes terminal0, full-core independent readback, copied/cmp
  # receipts and source checks succeeded. Failure paths retain their store.
  rm -r -- "$task_store"
  test ! -e "$task_store"
done
printf '%s\n' 'terminal0: all four actual socket arms independently recovered and stores retired'
