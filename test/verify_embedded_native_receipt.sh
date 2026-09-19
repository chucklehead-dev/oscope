#!/usr/bin/env bash
set -euo pipefail

# The failure artifact is intentionally less informative than a test log.  It
# is a fixed allowlist, so a child process cannot smuggle application payloads,
# endpoint URLs, local paths, or credentials into retained CI evidence.
receipt=${1:?usage: test/verify_embedded_native_receipt.sh RECEIPT}
test -f "$receipt" && test ! -L "$receipt"
test "$(wc -l < "$receipt")" = 1

case "$(cat "$receipt")" in
  '{:oscope.embedded.native.receipt/version 1 :result :passed :checks #{:generation-match :one-sdk-owner :terminal-unavailable :v1-unchanged :v2-preterminal}}'|\
  '{:oscope.embedded.native.receipt/version 1 :result :failed :checks #{}}')
    ;;
  *)
    echo "embedded native receipt is not an allowlisted categorical record" >&2
    exit 65
    ;;
esac
