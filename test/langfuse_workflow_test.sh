#!/usr/bin/env bash
set -euo pipefail

workflow=${LANGFUSE_WORKFLOW_PATH:-.github/workflows/langfuse-interop.yml}
test -f "$workflow"

grep -Fxq '  workflow_dispatch:' "$workflow"
event_count=$(awk '
  /^on:$/ { in_events=1; next }
  in_events && /^[^ ]/ { in_events=0 }
  in_events && /^  [A-Za-z_][A-Za-z0-9_]*:/ { count++ }
  END { print count + 0 }
' "$workflow")
[[ $event_count -eq 1 ]]
grep -Fxq '    environment: langfuse-interop' "$workflow"
[[ $(grep -Fc '          persist-credentials: false' "$workflow") -eq 2 ]]
grep -Fxq '          ref: 19e0ecf9e9f5e2c3f24ac8758f5d6953fd021774' "$workflow"
grep -Fxq '          echo "JOLT_BIN=$QUALIFIED_RUNTIME_BIN" >> "$GITHUB_ENV"' "$workflow"
grep -Fxq '          dirname "$QUALIFIED_RUNTIME_BIN" >> "$GITHUB_PATH"' "$workflow"
grep -Fxq '            https://raw.githubusercontent.com/jolt-lang/jolt/f3041a0e32ba0db1b92bd69b8ecb7b40f8b2e115/install \' "$workflow"
grep -Fxq '              --checksum 3dab6373f2028efaf7a0e41231fc316de866e55454023c037e9d5981be6358c2' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_BASE_URL: ${{ vars.OSCOPE_LANGFUSE_BASE_URL }}' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_PUBLIC_KEY: ${{ secrets.OSCOPE_LANGFUSE_PUBLIC_KEY }}' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_SECRET_KEY: ${{ secrets.OSCOPE_LANGFUSE_SECRET_KEY }}' "$workflow"
grep -Fxq '        run: test/langfuse_interop_env.sh' "$workflow"
! grep -Fq 'OSCOPE_LANGFUSE_OTLP_HEADERS' "$workflow"

if [[ ${LANGFUSE_WORKFLOW_MUTATION_CHECK:-0} = 0 ]]; then
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-langfuse-workflow-test.XXXXXX")
  trap 'rm -rf -- "$tmp"' EXIT INT TERM
  sed -e 's/vars\.OSCOPE_LANGFUSE_BASE_URL/vars.LANGFUSE_BASE_URL/' \
      -e 's/secrets\.OSCOPE_LANGFUSE_PUBLIC_KEY/secrets.LANGFUSE_PUBLIC_KEY/' \
      -e 's/secrets\.OSCOPE_LANGFUSE_SECRET_KEY/secrets.LANGFUSE_SECRET_KEY/' \
      "$workflow" > "$tmp/short-context-names.yml"
  if LANGFUSE_WORKFLOW_PATH="$tmp/short-context-names.yml" \
       LANGFUSE_WORKFLOW_MUTATION_CHECK=1 "$0" >/dev/null 2>&1; then
    printf '%s\n' 'FAIL: short Langfuse context names passed policy' >&2
    exit 1
  fi
fi

printf '%s\n' 'PASS: Langfuse workflow is manual, protected, and separately injected'
