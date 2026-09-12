#!/usr/bin/env bash
set -euo pipefail

: "${JOLT_CHDB_LIB:?set JOLT_CHDB_LIB to the qualified libchdb shared library}"
: "${OSCOPE_LANGFUSE_BASE_URL:?set the credential-free Langfuse base URL}"
: "${OSCOPE_LANGFUSE_OTLP_HEADERS:?set private OTLP headers for Langfuse}"

case "$JOLT_CHDB_LIB" in
  /*) ;;
  *) echo "JOLT_CHDB_LIB must be an absolute path" >&2; exit 2 ;;
esac

test -f "$JOLT_CHDB_LIB"

jolt -Srepro -M:test-langfuse
