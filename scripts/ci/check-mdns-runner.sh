#!/usr/bin/env bash
set -euo pipefail

if [[ "${RUNNER_ENVIRONMENT:-}" != "self-hosted" ]]; then
  echo "mDNS E2E requires a self-hosted runner on the target LAN." >&2
  exit 1
fi

if command -v ip >/dev/null 2>&1; then
  if ! ip link show | grep -Eq 'MULTICAST.*UP|UP.*MULTICAST'; then
    echo "No active multicast-capable interface was found." >&2
    exit 1
  fi
elif command -v ifconfig >/dev/null 2>&1; then
  if ! ifconfig | grep -Eiq 'MULTICAST'; then
    echo "No multicast-capable interface was found." >&2
    exit 1
  fi
else
  echo "Neither ip nor ifconfig is available to verify multicast support." >&2
  exit 1
fi

echo "Self-hosted multicast runner prerequisites are present."
echo "This gate does not claim product mDNS coverage; a native discovery suite must run here."
