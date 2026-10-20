#!/usr/bin/env bash
set -euo pipefail
task_out=${1:-/home/chuck/ai-src/evidence/oscope-socket-reader-512-pair-20261020}
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
sha256sum benchmark/oscope/socket_ingest.clj deps.edn src/oscope/server.clj src/oscope/otlp.clj src/oscope/export_options.clj research/socket-ingest-20261020/run-reader-pair.sh > "$task_out/sources.sha256"
for task_mode in codec codec-native-reader; do
  task_store=$(mktemp -d /tmp/oscope-socket-pair.XXXXXXXX)
  printf '%s\n' "$task_store" > "$task_out/$task_mode.store-path"
  cp /proc/loadavg "$task_out/$task_mode.load-before.txt"
  timeout --signal=TERM --kill-after=5s 240s "$task_wrapper" "$task_bin" -Srepro \
    -A:stock-span-codec -Sdeps '{:paths ["src" "benchmark"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "83b0e97d88a322e870c297c8e14261b727572d73"}}}' \
    -m oscope.socket-ingest writer "$task_store" "$task_mode" 512 8 > "$task_out/$task_mode.writer.log" 2>&1
  timeout --signal=TERM --kill-after=5s 90s "$task_wrapper" "$task_bin" -Srepro \
    -Sdeps '{:paths ["src" "benchmark"] :deps {org.clojure/data.json {:git/url "https://github.com/casselc/data.json.git" :git/sha "83b0e97d88a322e870c297c8e14261b727572d73"}}}' -m oscope.socket-ingest reader "$task_store" > "$task_out/$task_mode.reader.log" 2>&1
  cp /proc/loadavg "$task_out/$task_mode.load-after.txt"
  for task_receipt in writer reader seal; do
    cp "$task_store/$task_receipt.edn" "$task_out/$task_mode.$task_receipt.edn"
    cmp "$task_store/$task_receipt.edn" "$task_out/$task_mode.$task_receipt.edn"
  done
  bb -e '(require (quote clojure.edn))
    (let [[w r] (map #(clojure.edn/read-string (slurp %)) *command-line-args*)]
      (assert (= 512 (:batch-size w))) (assert (= 8 (:rounds w)))
      (assert (= 4096 (:confirmed-rows w))) (assert (= 8 (count (:samples-nanos w))))
      (assert (every? pos? (:samples-nanos w)))
      (assert (<= (:wire-bytes w) 1048576))
      (assert (= 9 (:codec-calls w))) (assert (= 9 (:stream-calls w)))
      (assert (= 8 (count (get-in w [:phase-samples :json-document-read]))))
      (assert (= (if (= "codec-native-reader" (:mode w)) 9 0) (:reader-requests w)))
      (assert (= 4608 (:rows r))) (assert (= 22 (:core-columns r)))
      (assert (:independent-reader r)))' "$task_out/$task_mode.writer.edn" "$task_out/$task_mode.reader.edn"
  sha256sum -c "$task_out/sources.sha256" > "$task_out/$task_mode.source-after.log"
  du -sk "$task_store" > "$task_out/$task_mode.retired-kib.txt"
  case "$task_store" in /tmp/oscope-socket-pair.????????) ;; *) exit 91;; esac
  test ! -L "$task_store"
  test "$(realpath "$task_store")" = "$task_store"
  # Both exact processes terminal0, full-core independent readback, copied/cmp
  # receipts and source checks succeeded. Failure paths retain their store.
  rm -r -- "$task_store"
  test ! -e "$task_store"
done
printf '%s\n' 'terminal0: both actual socket arms independently recovered and stores retired'
