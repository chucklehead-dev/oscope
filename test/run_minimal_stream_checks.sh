#!/usr/bin/env bash
set -euo pipefail
task_jolt=${OSCOPE_STREAM_JOLT_BIN:?explicit qualified runtime launcher required}
cd "$(dirname "$0")/fixtures/minimal-embedded-app"
exec "$task_jolt" -Srepro -Sdeps '{:paths ["src" "test"]}' -A:stream-test -e \
  "(require 'minimal-embedded-stream-check-test 'clojure.test)
   (let [r (clojure.test/run-tests 'minimal-embedded-stream-check-test)]
     (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
