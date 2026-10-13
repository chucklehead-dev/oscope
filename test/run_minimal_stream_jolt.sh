#!/usr/bin/env bash
# Launcher for the existing private-output native fixture controller.
set -euo pipefail
task_jolt=${OSCOPE_STREAM_JOLT_BIN:?explicit qualified runtime launcher required}
test "$#" -eq 6
test "$1" = -Srepro
test "$2" = -M:test
case "$3" in writer|reader|mutant) ;; *) exit 64 ;; esac
test "$("$task_jolt" --version)" = 'jolt v0.8.17-49-g7c57cf7e'
export JOLT_AOT_CACHE=0
shift 2
exec "$task_jolt" -Srepro -M:stream-test "$@"
