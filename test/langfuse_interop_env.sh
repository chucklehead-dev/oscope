#!/usr/bin/env bash
set +x
set -euo pipefail

fail() {
  printf '%s\n' 'FAIL: invalid Langfuse credential configuration' >&2
  exit 2
}

base_url=''
public_key=''
secret_key=''
encoded=''
header=''

cleanup() {
  unset base_url public_key secret_key encoded header
  unset OSCOPE_LANGFUSE_PUBLIC_KEY OSCOPE_LANGFUSE_SECRET_KEY
  unset OSCOPE_LANGFUSE_BASE_URL OSCOPE_LANGFUSE_OTLP_HEADERS
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

valid_value() {
  local value=$1
  [[ -n $value ]] || return 1
  case "$value" in
    *[$'\r\n\t ']*|*\'*|*\"*) return 1 ;;
  esac
}

read_credential_file() {
  local env_file=$1
  local line=''
  local key=''
  local value=''
  local line_count=0
  local mode=''
  local permissions=0
  local seen_base=0
  local seen_public=0
  local seen_secret=0

  case "$env_file" in
    /*) ;;
    *) fail ;;
  esac
  case "$env_file" in
    *[$'\r\n']*) fail ;;
  esac
  [[ -f $env_file && ! -L $env_file && -r $env_file ]] || fail

  [[ -x /usr/bin/stat && -x /usr/bin/base64 && -x /usr/bin/tr ]] || fail
  if mode=$(/usr/bin/stat -c '%a' -- "$env_file" 2>/dev/null); then
    :
  elif mode=$(/usr/bin/stat -f '%Lp' -- "$env_file" 2>/dev/null); then
    :
  else
    fail
  fi
  [[ $mode =~ ^[0-7]{3,4}$ ]] || fail
  permissions=$((8#$mode))
  (( (permissions & 0400) != 0 )) || fail
  (( (permissions & 077) == 0 )) || fail
  (( (permissions & 0111) == 0 )) || fail

  while true; do
    line=''
    if IFS= read -r line; then
      :
    elif [[ -n $line ]]; then
      fail
    else
      break
    fi
    line_count=$((line_count + 1))
    case "$line" in
      *=*) key=${line%%=*}; value=${line#*=} ;;
      *) fail ;;
    esac
    valid_value "$value" || fail
    case "$key" in
      OSCOPE_LANGFUSE_BASE_URL)
        (( seen_base == 0 )) || fail
        seen_base=1
        base_url=$value
        ;;
      OSCOPE_LANGFUSE_PUBLIC_KEY)
        (( seen_public == 0 )) || fail
        seen_public=1
        public_key=$value
        ;;
      OSCOPE_LANGFUSE_SECRET_KEY)
        (( seen_secret == 0 )) || fail
        seen_secret=1
        secret_key=$value
        ;;
      *) fail ;;
    esac
  done < "$env_file"

  [[ $line_count -eq 3 && $seen_base -eq 1 &&
     $seen_public -eq 1 && $seen_secret -eq 1 ]] || fail
}

[[ $# -le 1 ]] || fail
[[ ! ${OSCOPE_LANGFUSE_OTLP_HEADERS+x} ]] || fail

if [[ $# -eq 1 ]]; then
  [[ ! ${OSCOPE_LANGFUSE_BASE_URL+x} &&
     ! ${OSCOPE_LANGFUSE_PUBLIC_KEY+x} &&
     ! ${OSCOPE_LANGFUSE_SECRET_KEY+x} ]] || fail
  read_credential_file "$1"
else
  [[ ${OSCOPE_LANGFUSE_BASE_URL+x} &&
     ${OSCOPE_LANGFUSE_PUBLIC_KEY+x} &&
     ${OSCOPE_LANGFUSE_SECRET_KEY+x} ]] || fail
  base_url=$OSCOPE_LANGFUSE_BASE_URL
  public_key=$OSCOPE_LANGFUSE_PUBLIC_KEY
  secret_key=$OSCOPE_LANGFUSE_SECRET_KEY
  valid_value "$base_url" || fail
  valid_value "$public_key" || fail
  valid_value "$secret_key" || fail
fi

case "$public_key" in *:*) fail ;; esac
case "$secret_key" in *:*) fail ;; esac

unset OSCOPE_LANGFUSE_PUBLIC_KEY OSCOPE_LANGFUSE_SECRET_KEY
encoded=$(printf '%s' "$public_key:$secret_key" | /usr/bin/base64 | /usr/bin/tr -d '\r\n')
[[ -n $encoded ]] || fail
header="Authorization=Basic $encoded,x-langfuse-ingestion-version=4"

if [[ ${GITHUB_ACTIONS:-} == true ]]; then
  printf '::add-mask::%s\n' "$encoded"
  printf '::add-mask::%s\n' "$header"
fi

export OSCOPE_LANGFUSE_BASE_URL=$base_url
export OSCOPE_LANGFUSE_OTLP_HEADERS=$header

script_source=${BASH_SOURCE[0]}
case "$script_source" in
  /*) ;;
  *) script_source=$PWD/$script_source ;;
esac
script_dir=${script_source%/*}
script_dir=$(CDPATH= cd -- "$script_dir" && pwd)
"$script_dir/langfuse_interop.sh"
