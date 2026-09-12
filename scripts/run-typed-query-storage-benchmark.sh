#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ( "$1" != "smoke" && "$1" != "representative" ) ]]; then
  echo "usage: scripts/run-typed-query-storage-benchmark.sh smoke|representative" >&2
  exit 64
fi

profile=$1
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "typed query/storage benchmark requires a clean worktree" >&2
  exit 65
fi

source_sha=$(git rev-parse --verify 'HEAD^{commit}')
if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "typed query/storage benchmark could not resolve an exact commit" >&2
  exit 66
fi

export OSCOPE_BENCHMARK_SOURCE_SHA=$source_sha
export OSCOPE_BENCHMARK_SOURCE_STATE=clean
exec jolt -M:bench-typed-query-storage --profile "$profile"
