#!/usr/bin/env bash
# Build liboscope_core, then run the Jolt checks, agent demo and benchmark
# against it. Needs: CHDB_DIR (libchdb 26.7.3), cargo, and stock jolt v0.8.6:
#   curl -fsSL https://raw.githubusercontent.com/jolt-lang/jolt/v0.8.6/install | bash -s -- --version 0.8.6
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${CHDB_DIR:?set CHDB_DIR to the directory holding libchdb.so}"
target=${CARGO_TARGET_DIR:-$here/../target}
jolt=${JOLT:-jolt}
out=${OUT_DIR:-$here/.demo}

(cd "$here/.." && CHDB_DIR="$CHDB_DIR" CARGO_TARGET_DIR="$target" cargo build --release)
export OSCOPE_LIB="$target/release/liboscope_core.so"
mkdir -p "$out"
cd "$here"
"$jolt" -M:check
rm -rf "$out/agent-data"; OSCOPE_DB="$out/agent-data" "$jolt" -M:demo "${SESSIONS:-2000}"
rm -rf "$out/bench-data"; OSCOPE_DB="$out/bench-data" "$jolt" -M:bench
