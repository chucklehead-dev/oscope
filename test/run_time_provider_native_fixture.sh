#!/usr/bin/env bash
set -euo pipefail

# Oscope #119: independently resolve, compile, then hand off one local Durable
# object from writer to snapshot reader.  No application or embedded fixture is
# reused as evidence here.
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jolt_bin=${JOLT_BIN:-jolt}
fixture_root="$repo_root/test/fixtures/time-provider-durable"
run_root=$(mktemp -d "${RUNNER_TEMP:-/tmp}/oscope-time-provider.XXXXXXXX")
trap 'rm -rf -- "$run_root"' EXIT

provider_roots() {
  local fixture=$1 classpath path
  classpath=$(cd -- "$fixture" && "$jolt_bin" -Srepro -Spath)
  local -A roots=()
  IFS=: read -r -a paths <<< "$classpath"
  for path in "${paths[@]}"; do
    if [[ -f "$path/jolt/time.clj" || -f "$path/jolt/time.cljc" ]]; then
      roots["$path"]=1
    fi
  done
  printf '%s\n' "${!roots[@]}" | LC_ALL=C sort
}

canonical_time_root() {
  case "$1" in
    */https___github.com_chucklehead-dev_time.git/2494b21b25cd959573c3e6050cd475e4bf302fdb/src) return 0 ;;
    *) return 1 ;;
  esac
}

upstream_time_root() {
  case "$1" in
    */https___github.com_jolt-lang_time.git/70dfb7981ef4ed70c5d142109d56a90edfb79cef/src) return 0 ;;
    *) return 1 ;;
  esac
}

check_single_canonical_provider() {
  local fixture=$1
  local -a roots=()
  mapfile -t roots < <(provider_roots "$fixture")
  test "${#roots[@]}" = 1
  canonical_time_root "${roots[0]}"
}

check_expected_duplicate_providers() {
  local -a roots=()
  mapfile -t roots < <(provider_roots "$fixture_root/duplicate")
  test "${#roots[@]}" = 2
  canonical_time_root "${roots[0]}" || canonical_time_root "${roots[1]}"
  upstream_time_root "${roots[0]}" || upstream_time_root "${roots[1]}"
}

check_single_canonical_provider "$fixture_root/producer"
check_single_canonical_provider "$fixture_root/reader"
# The red graph must be rejected before a source or native program runs.
check_expected_duplicate_providers

(cd -- "$fixture_root/producer" && "$jolt_bin" -Srepro build -m time-provider-durable.producer -o "$run_root/producer")
(cd -- "$fixture_root/reader" && "$jolt_bin" -Srepro build -m time-provider-durable.reader -o "$run_root/reader")
"$run_root/producer" "$run_root/store"
"$run_root/reader" "$run_root/store"
printf '%s\n' "oscope time-provider native fixture: PASS"
