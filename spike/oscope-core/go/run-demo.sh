#!/usr/bin/env bash
# Build liboscope_core, weave the shop app with Orchestrion, run it, and print
# what it recorded. Needs: CHDB_DIR (libchdb 26.7.3), cargo, go (1.25+ is
# fetched automatically), and orchestrion (`go install github.com/DataDog/orchestrion@v1.13.1`).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${CHDB_DIR:?set CHDB_DIR to the directory holding libchdb.so}"
target=${CARGO_TARGET_DIR:-$here/../target}
out=${OUT_DIR:-$here/.demo}
export GOTOOLCHAIN=auto
export CGO_LDFLAGS="-L$CHDB_DIR -L$target/release -Wl,-rpath,$CHDB_DIR -Wl,-rpath,$target/release"

(cd "$here/.." && CHDB_DIR="$CHDB_DIR" CARGO_TARGET_DIR="$target" cargo build --release)
mkdir -p "$out"
orchestrion=${ORCHESTRION:-$(go env GOPATH)/bin/orchestrion}
(cd "$here" && "$orchestrion" go build -o "$out/shop" ./cmd/shop && go build -o "$out/report" ./cmd/report)

cd "$out"
rm -rf oscope-data
./shop -n "${N:-2000}" 2>/dev/null
./report ./oscope-data
