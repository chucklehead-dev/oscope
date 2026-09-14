#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d "${TMPDIR:-/tmp}/oscope-langfuse-env-test.XXXXXX")
cleanup() {
  rm -rf -- "$root"
}
trap cleanup EXIT HUP INT TERM

cp test/langfuse_interop_env.sh "$root/langfuse_interop_env.sh"
chmod 700 "$root/langfuse_interop_env.sh"

cat > "$root/langfuse_interop.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'argc=%s\n' "$#"
  printf 'base=%s\n' "$OSCOPE_LANGFUSE_BASE_URL"
  printf 'header=%s\n' "$OSCOPE_LANGFUSE_OTLP_HEADERS"
  printf 'public-exported=%s\n' "${OSCOPE_LANGFUSE_PUBLIC_KEY+x}"
  printf 'secret-exported=%s\n' "${OSCOPE_LANGFUSE_SECRET_KEY+x}"
} > "$LANGFUSE_WRAPPER_TEST_CAPTURE"
EOF
chmod 700 "$root/langfuse_interop.sh"

base_url='https://langfuse.example'
public_key='pk-local-test-41'
secret_key='sk-local-test-73'
expected_encoded=$(printf '%s' "$public_key:$secret_key" | base64 | tr -d '\r\n')
expected_header="Authorization=Basic $expected_encoded,x-langfuse-ingestion-version=4"

write_file() {
  local path=$1
  shift
  printf '%s' "$*" > "$path"
  chmod 600 "$path"
}

assert_rejected() {
  local label=$1
  shift
  local output="$root/rejected.out"
  if "$@" > "$output" 2>&1; then
    printf 'FAIL: accepted %s\n' "$label" >&2
    exit 1
  fi
  if grep -Fq "$public_key" "$output" || grep -Fq "$secret_key" "$output"; then
    printf 'FAIL: rejection disclosed a credential\n' >&2
    exit 1
  fi
}

credentials="$root/credentials.env"
capture="$root/capture"
log="$root/run.log"
write_file "$credentials" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key
"

LANGFUSE_WRAPPER_TEST_CAPTURE=$capture \
  "$root/langfuse_interop_env.sh" "$credentials" > "$log" 2>&1
grep -Fxq 'argc=0' "$capture"
grep -Fxq "base=$base_url" "$capture"
grep -Fxq "header=$expected_header" "$capture"
grep -Fxq 'public-exported=' "$capture"
grep -Fxq 'secret-exported=' "$capture"
! grep -Fq "$public_key" "$log"
! grep -Fq "$secret_key" "$log"

env_capture="$root/env-capture"
spy_marker="$root/path-helper-invoked"
spy_bin="$root/spy-bin"
mkdir "$spy_bin"
cat > "$spy_bin/base64" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
touch "$LANGFUSE_WRAPPER_PATH_SPY_MARKER"
exit 91
EOF
cat > "$spy_bin/tr" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
touch "$LANGFUSE_WRAPPER_PATH_SPY_MARKER"
exit 92
EOF
chmod 700 "$spy_bin/base64" "$spy_bin/tr"

LANGFUSE_WRAPPER_TEST_CAPTURE=$env_capture \
LANGFUSE_WRAPPER_PATH_SPY_MARKER=$spy_marker \
OSCOPE_LANGFUSE_BASE_URL=$base_url \
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key \
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key \
PATH="$spy_bin:$PATH" \
  /usr/bin/bash "$root/langfuse_interop_env.sh" > "$log" 2>&1
cmp "$capture" "$env_capture"
[[ ! -e $spy_marker ]]

mask_log="$root/mask.log"
LANGFUSE_WRAPPER_TEST_CAPTURE=$env_capture \
OSCOPE_LANGFUSE_BASE_URL=$base_url \
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key \
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key \
GITHUB_ACTIONS=true \
  "$root/langfuse_interop_env.sh" > "$mask_log" 2>&1
grep -Fxq "::add-mask::$expected_encoded" "$mask_log"
grep -Fxq "::add-mask::$expected_header" "$mask_log"
[[ $(grep -c '^::add-mask::' "$mask_log") -eq 2 ]]
! grep -Fq "$public_key" "$mask_log"
! grep -Fq "$secret_key" "$mask_log"

xtrace_log="$root/xtrace.log"
LANGFUSE_WRAPPER_TEST_CAPTURE=$env_capture \
OSCOPE_LANGFUSE_BASE_URL=$base_url \
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key \
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key \
  bash -x "$root/langfuse_interop_env.sh" > "$xtrace_log" 2>&1
! grep -Fq "$public_key" "$xtrace_log"
! grep -Fq "$secret_key" "$xtrace_log"

assert_rejected 'ambiguous file plus environment' env \
  OSCOPE_LANGFUSE_BASE_URL="$base_url" \
  OSCOPE_LANGFUSE_PUBLIC_KEY="$public_key" \
  OSCOPE_LANGFUSE_SECRET_KEY="$secret_key" \
  "$root/langfuse_interop_env.sh" "$credentials"

assert_rejected 'ambient combined header' env \
  OSCOPE_LANGFUSE_OTLP_HEADERS='Authorization=Basic stale' \
  "$root/langfuse_interop_env.sh" "$credentials"

assert_rejected 'incomplete environment mode' env \
  OSCOPE_LANGFUSE_BASE_URL="$base_url" \
  OSCOPE_LANGFUSE_PUBLIC_KEY="$public_key" \
  "$root/langfuse_interop_env.sh"

duplicate="$root/duplicate.env"
write_file "$duplicate" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
"
assert_rejected 'duplicate key' "$root/langfuse_interop_env.sh" "$duplicate"

unknown="$root/unknown.env"
write_file "$unknown" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
UNEXPECTED=$secret_key
"
assert_rejected 'unknown key' "$root/langfuse_interop_env.sh" "$unknown"

missing="$root/missing.env"
write_file "$missing" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
"
assert_rejected 'missing key' "$root/langfuse_interop_env.sh" "$missing"

quoted="$root/quoted.env"
write_file "$quoted" \
"OSCOPE_LANGFUSE_BASE_URL=\"$base_url\"
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key
"
assert_rejected 'quoted value' "$root/langfuse_interop_env.sh" "$quoted"

spaced="$root/spaced.env"
write_file "$spaced" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY= $public_key
OSCOPE_LANGFUSE_SECRET_KEY=$secret_key
"
assert_rejected 'whitespace value' "$root/langfuse_interop_env.sh" "$spaced"

carriage_return="$root/carriage-return.env"
printf 'OSCOPE_LANGFUSE_BASE_URL=%s\r\nOSCOPE_LANGFUSE_PUBLIC_KEY=%s\nOSCOPE_LANGFUSE_SECRET_KEY=%s\n' \
  "$base_url" "$public_key" "$secret_key" > "$carriage_return"
chmod 600 "$carriage_return"
assert_rejected 'carriage return' "$root/langfuse_interop_env.sh" "$carriage_return"

unterminated="$root/unterminated.env"
printf 'OSCOPE_LANGFUSE_BASE_URL=%s\nOSCOPE_LANGFUSE_PUBLIC_KEY=%s\nOSCOPE_LANGFUSE_SECRET_KEY=%s' \
  "$base_url" "$public_key" "$secret_key" > "$unterminated"
chmod 600 "$unterminated"
assert_rejected 'unterminated final line' "$root/langfuse_interop_env.sh" "$unterminated"

trailing="$root/trailing.env"
printf 'OSCOPE_LANGFUSE_BASE_URL=%s\nOSCOPE_LANGFUSE_PUBLIC_KEY=%s\nOSCOPE_LANGFUSE_SECRET_KEY=%s\nUNEXPECTED=trailing' \
  "$base_url" "$public_key" "$secret_key" > "$trailing"
chmod 600 "$trailing"
assert_rejected 'unterminated trailing data' \
  "$root/langfuse_interop_env.sh" "$trailing"

malicious="$root/malicious.env"
marker="$root/must-not-exist"
write_file "$malicious" \
"OSCOPE_LANGFUSE_BASE_URL=$base_url
OSCOPE_LANGFUSE_PUBLIC_KEY=$public_key
touch $marker
"
assert_rejected 'executable line' "$root/langfuse_interop_env.sh" "$malicious"
[[ ! -e $marker ]]

world_readable="$root/world-readable.env"
cp "$credentials" "$world_readable"
chmod 644 "$world_readable"
assert_rejected 'group/world readable file' \
  "$root/langfuse_interop_env.sh" "$world_readable"

executable="$root/executable.env"
cp "$credentials" "$executable"
chmod 700 "$executable"
assert_rejected 'executable credential file' \
  "$root/langfuse_interop_env.sh" "$executable"

linked="$root/linked.env"
ln -s "$credentials" "$linked"
assert_rejected 'symlink credential file' \
  "$root/langfuse_interop_env.sh" "$linked"

assert_rejected 'relative credential path' \
  "$root/langfuse_interop_env.sh" relative.env

printf '%s\n' 'PASS: Langfuse credential wrapper is strict and non-executing'
