#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ( "$1" != "smoke" && "$1" != "representative" ) ]]; then
  echo "usage: scripts/run-typed-query-storage-benchmark.sh smoke|representative [1|EVEN_REPETITIONS]" >&2
  exit 64
fi

profile=$1
repetitions=${2:-1}
if [[ ! "$repetitions" =~ ^(1|2|4|6|8|10)$ ]]; then
  echo "process repetitions must be 1 or an even integer from 2 through 10" >&2
  exit 64
fi
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

verify_source() {
  if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo "typed query/storage benchmark source changed during the run" >&2
    exit 65
  fi
  current_sha=$(git rev-parse --verify 'HEAD^{commit}')
  if [[ "$current_sha" != "$source_sha" ]]; then
    echo "typed query/storage benchmark revision changed during the run" >&2
    exit 66
  fi
}

export OSCOPE_BENCHMARK_SOURCE_SHA=$source_sha
export OSCOPE_BENCHMARK_SOURCE_STATE=clean

profile_dir=target/profiles
final_output=$profile_dir/typed-query-storage.edn
mkdir -p "$profile_dir"
run_dir=$(mktemp -d "$profile_dir/.typed-query-storage.XXXXXX")
cleanup() {
  rm -rf -- "$run_dir"
}
trap cleanup EXIT

shards=()
for (( index = 0; index < repetitions; index++ )); do
  verify_source
  shard="$run_dir/repetition-$index.edn"
  shards+=("$shard")
  jolt -Srepro -M:bench-typed-query-storage \
    --profile "$profile" \
    --repetition-index "$index" \
    --repetition-count "$repetitions" \
    --output "$shard"
done

verify_source
jolt -Srepro -Sdeps '{:paths ["benchmark"]}' \
  -m oscope.typed-query-storage-report \
  --output "$run_dir/combined.edn" \
  "${shards[@]}"

verify_source
mv -- "$run_dir/combined.edn" "$final_output"
echo "validated artifact: $final_output"
