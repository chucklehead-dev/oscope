#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
crash_script="$repo_root/test/durable_crash_reopen.sh"
edn_bin=${JOLT_EDN_BIN:-jolt}
valid_root=${1:?usage: durable_crash_root_test.sh /absolute/path/to/jolt-chdb}
valid_exporter_root=${2:?usage: durable_crash_root_test.sh /absolute/path/to/jolt-chdb /absolute/path/to/jolt-otel-clickhouse}

if ! command -v "$edn_bin" >/dev/null 2>&1; then
  echo "JOLT_EDN_BIN must name an executable Jolt runtime" >&2
  exit 2
fi

case "$valid_root" in
  /*) ;;
  *) echo "test root must be absolute" >&2; exit 2 ;;
esac

test -d "$valid_root" && test ! -L "$valid_root"
test -f "$valid_root/deps.edn"
test -f "$valid_root/src/jdbc/chdb/durable.clj"
test -d "$valid_root/resources"
test -d "$valid_exporter_root" && test ! -L "$valid_exporter_root"
test -f "$valid_exporter_root/deps.edn"
test -f "$valid_exporter_root/src/otel/exporter/chdb.clj"

# The checker intentionally runs before JOLT_CHDB_LIB validation: these are
# pure path-resolution controls, not a native qualification substitute.
test "$(env -u JOLT_CHDB_ROOT bash "$crash_script" --check-jolt-chdb-root)" \
  = 'default-relative=../../../jolt-chdb'
test "$(env JOLT_CHDB_ROOT="$valid_root" bash "$crash_script" --check-jolt-chdb-root)" \
  = "override=$valid_root"

# A linked Oscope worktree must derive the exporter from the main worktree's
# workspace, while an explicit local root remains the deterministic override.
derived=$(env JOLT_CHDB_ROOT="$valid_root" \
  bash "$crash_script" --check-local-roots)
test "$derived" = "$(printf 'chdb=%s\nexporter=%s' "$valid_root" "$valid_exporter_root")"
explicit=$(env JOLT_CHDB_ROOT="$valid_root" \
  JOLT_OTEL_CLICKHOUSE_ROOT="$valid_exporter_root" \
  bash "$crash_script" --check-local-roots)
test "$explicit" = "$(printf 'chdb=%s\nexporter=%s' "$valid_root" "$valid_exporter_root")"

# Quotes and backslashes are escaped before the private deps.edn is written;
# newlines fail before any build or native-library validation is attempted.
quoted_root=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-quote.XXXXXX")
quoted_exporter=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-exporter-quote.XXXXXX")
newline_root=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-newline.XXXXXX")
newline_stderr=$(mktemp "${TMPDIR:-/tmp}/oscope-durable-newline-stderr.XXXXXX")
edn_parse_dir=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-edn-parse.XXXXXX")
cleanup_quoted_roots() {
  rm -rf -- "$quoted_root" "$quoted_exporter" "$newline_root" "$edn_parse_dir"
  rm -f -- "$newline_stderr"
}
trap cleanup_quoted_roots EXIT INT TERM
ln -s "$valid_root/deps.edn" "$quoted_root/deps.edn"
mkdir -p "$quoted_root/src/jdbc/chdb" "$quoted_root/resources"
ln -s "$valid_root/src/jdbc/chdb/durable.clj" "$quoted_root/src/jdbc/chdb/durable.clj"
ln -s "$valid_exporter_root/deps.edn" "$quoted_exporter/deps.edn"
mkdir -p "$quoted_exporter/src/otel/exporter"
ln -s "$valid_exporter_root/src/otel/exporter/chdb.clj" "$quoted_exporter/src/otel/exporter/chdb.clj"

# Rename the validated fixtures into names that exercise both EDN escaping
# characters. Their children remain the same minimal checkout shape.
quoted_root_with_syntax="${quoted_root}\"\\"
quoted_exporter_with_syntax="${quoted_exporter}\"\\"
mv -- "$quoted_root" "$quoted_root_with_syntax"
mv -- "$quoted_exporter" "$quoted_exporter_with_syntax"
quoted_root=$quoted_root_with_syntax
quoted_exporter=$quoted_exporter_with_syntax
quoted_deps=$(env JOLT_CHDB_ROOT="$quoted_root" \
  JOLT_OTEL_CLICKHOUSE_ROOT="$quoted_exporter" \
  bash "$crash_script" --print-isolated-deps)
escaped_quoted_root=${quoted_root//\\/\\\\}
escaped_quoted_root=${escaped_quoted_root//\"/\\\"}
escaped_quoted_exporter=${quoted_exporter//\\/\\\\}
escaped_quoted_exporter=${escaped_quoted_exporter//\"/\\\"}
printf '%s\n' "$quoted_deps" | \
  grep -F -- ":local/root \"$escaped_quoted_root\"" >/dev/null
printf '%s\n' "$quoted_deps" | \
  grep -F -- ":local/root \"$escaped_quoted_exporter\"" >/dev/null

# Parsing, rather than searching rendered text, proves that quote and
# backslash escaping remains data in all three local-root declarations.
parsed_quoted_roots=$(cd "$edn_parse_dir" && printf '%s\n' "$quoted_deps" | "$edn_bin" -Srepro -e '
(let [deps (clojure.edn/read-string (slurp *in*))]
  (doseq [library [(symbol "io.github.chucklehead-dev/oscope")
                   (symbol "io.github.chucklehead-dev/jolt-chdb")
                   (symbol "io.github.chucklehead-dev/jolt-otel-clickhouse")]]
    (println (get-in deps [:deps library :local/root]))))')
test "$parsed_quoted_roots" = "$(printf '%s\n%s\n%s' \
  "$repo_root" "$quoted_root" "$quoted_exporter")"

# A path with a newline must pass checkout-shape validation before it reaches
# EDN encoding; otherwise this would prove only the earlier missing-root path.
ln -s "$valid_root/deps.edn" "$newline_root/deps.edn"
mkdir -p "$newline_root/src/jdbc/chdb" "$newline_root/resources"
ln -s "$valid_root/src/jdbc/chdb/durable.clj" \
  "$newline_root/src/jdbc/chdb/durable.clj"
newline_root_with_control="${newline_root}"$'\n'"rejected"
mv -- "$newline_root" "$newline_root_with_control"
newline_root=$newline_root_with_control
test -d "$newline_root" && test ! -L "$newline_root"
test -f "$newline_root/deps.edn"
test -f "$newline_root/src/jdbc/chdb/durable.clj"
test -d "$newline_root/resources"
if env JOLT_CHDB_ROOT="$newline_root" \
    JOLT_OTEL_CLICKHOUSE_ROOT="$valid_exporter_root" \
    bash "$crash_script" --print-isolated-deps >/dev/null 2>"$newline_stderr"; then
  echo "newline JOLT_CHDB_ROOT unexpectedly accepted" >&2
  exit 1
fi
grep -Fx 'local checkout paths must not contain control characters' \
  "$newline_stderr" >/dev/null

success_tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-crash-root-success.XXXXXX")
failure_tmp=$(mktemp -d "${TMPDIR:-/tmp}/oscope-durable-crash-root-failure.XXXXXX")
success_stderr=$(mktemp "${TMPDIR:-/tmp}/oscope-durable-crash-root-success-stderr.XXXXXX")
failure_stderr=$(mktemp "${TMPDIR:-/tmp}/oscope-durable-crash-root-failure-stderr.XXXXXX")
cleanup() {
  rm -f -- "$success_stderr" "$failure_stderr"
  rmdir -- "$success_tmp" "$failure_tmp" 2>/dev/null || true
  cleanup_quoted_roots
}
trap cleanup EXIT INT TERM

# A successful lightweight check removes its private evidence directory and
# emits no retention marker. TMPDIR lets this control prove cleanup without
# looking into a shared /tmp namespace.
TMPDIR="$success_tmp" env -u JOLT_CHDB_ROOT \
  bash "$crash_script" --check-jolt-chdb-root >/dev/null 2>"$success_stderr"
test ! -s "$success_stderr"
test -z "$(find "$success_tmp" -mindepth 1 -maxdepth 1 -print -quit)"

# A failed lightweight check retains its mktemp-private directory and reports
# only that path. The test removes its own known-empty fixture afterwards;
# production failures remain available to the operator.
if TMPDIR="$failure_tmp" JOLT_CHDB_ROOT="/definitely/missing/jolt-chdb" \
    bash "$crash_script" --check-jolt-chdb-root >/dev/null 2>"$failure_stderr"; then
  echo "missing JOLT_CHDB_ROOT unexpectedly accepted" >&2
  exit 1
fi
retained=$(sed -n 's#^RETAINED: Durable crash evidence directory: ##p' "$failure_stderr")
test -n "$retained"
case "$retained" in
  "$failure_tmp"/oscope-durable-crash.*) ;;
  *) echo "failure retention path escaped its private TMPDIR" >&2; exit 1 ;;
esac
test -d "$retained" && test ! -L "$retained"
test "$(stat -c '%a' "$retained")" = 700
rmdir -- "$retained"
test -z "$(find "$failure_tmp" -mindepth 1 -maxdepth 1 -print -quit)"

echo "PASS: Durable crash root and failure-evidence cleanup controls"
