#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/langfuse-interop.yml
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
grep -Fxq '          ref: dbc2db22130c7e783739c79bc24691dcbba21906' "$workflow"
grep -Fxq '            https://raw.githubusercontent.com/jolt-lang/jolt/f3041a0e32ba0db1b92bd69b8ecb7b40f8b2e115/install \' "$workflow"
grep -Fxq '              --checksum 3dab6373f2028efaf7a0e41231fc316de866e55454023c037e9d5981be6358c2' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_BASE_URL: ${{ vars.LANGFUSE_BASE_URL }}' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_PUBLIC_KEY: ${{ secrets.LANGFUSE_PUBLIC_KEY }}' "$workflow"
grep -Fxq '          OSCOPE_LANGFUSE_SECRET_KEY: ${{ secrets.LANGFUSE_SECRET_KEY }}' "$workflow"
grep -Fxq '        run: test/langfuse_interop_env.sh' "$workflow"
! grep -Fq 'OSCOPE_LANGFUSE_OTLP_HEADERS' "$workflow"

printf '%s\n' 'PASS: Langfuse workflow is manual, protected, and separately injected'
