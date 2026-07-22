#!/usr/bin/env bash
set -euo pipefail

url="${1:?usage: wait-for-http.sh URL [TIMEOUT_SECONDS]}"
timeout_seconds="${2:-180}"
deadline=$((SECONDS + timeout_seconds))

until curl --fail --silent --show-error "$url" >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    echo "Timed out after ${timeout_seconds}s waiting for ${url}" >&2
    exit 1
  fi
  sleep 2
done

echo "Ready: ${url}"
