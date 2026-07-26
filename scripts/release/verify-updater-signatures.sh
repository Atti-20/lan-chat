#!/usr/bin/env bash
set -euo pipefail

candidates_root="${1:?usage: verify-updater-signatures.sh CANDIDATES_ROOT}"
: "${TAURI_UPDATER_PUBLIC_KEY:?TAURI_UPDATER_PUBLIC_KEY is required}"

if ! command -v minisign >/dev/null 2>&1; then
  echo "minisign is required to verify updater artifacts." >&2
  exit 1
fi
if [[ ! -d "$candidates_root" ]]; then
  echo "Candidates root does not exist: ${candidates_root}" >&2
  exit 1
fi

decode_base64() {
  base64 --decode 2>/dev/null || base64 -D 2>/dev/null
}

public_key="$(
  printf '%s\n' "$TAURI_UPDATER_PUBLIC_KEY" \
    | awk 'NF && $0 !~ /^untrusted comment:/ { value=$0 } END { print value }'
)"
if [[ -z "$public_key" ]]; then
  echo "TAURI_UPDATER_PUBLIC_KEY does not contain a minisign public key." >&2
  exit 1
fi

decoded_public_key="$(
  printf '%s' "$public_key" | decode_base64 || true
)"
if [[ "$decoded_public_key" == *"untrusted comment:"* ]]; then
  public_key="$(
    printf '%s\n' "$decoded_public_key" \
      | awk 'NF && $0 !~ /^untrusted comment:/ { value=$0 } END { print value }'
  )"
fi

find_one() {
  local pattern="$1"
  local count
  count="$(
    find "$candidates_root" -type f -name "$pattern" -print \
      | awk 'END { print NR }'
  )"
  if [[ "$count" != "1" ]]; then
    echo "Expected exactly one ${pattern}; found ${count}." >&2
    find "$candidates_root" -type f -name "$pattern" -print >&2
    return 1
  fi
  find "$candidates_root" -type f -name "$pattern" -print | head -n 1
}

verify_pair() {
  local artifact="$1"
  local signature="${artifact}.sig"
  local decoded_signature
  local verification_signature="$signature"

  if [[ ! -s "$signature" ]]; then
    echo "Missing updater signature for ${artifact}: ${signature}" >&2
    return 1
  fi

  decoded_signature="$(
    mktemp "${TMPDIR:-/tmp}/meshx-updater-signature.XXXXXX"
  )"
  if decode_base64 <"$signature" >"$decoded_signature" \
    && grep -Fq 'untrusted comment:' "$decoded_signature"; then
    verification_signature="$decoded_signature"
  fi

  if ! minisign -Vm "$artifact" -x "$verification_signature" -P "$public_key"; then
    rm -f "$decoded_signature"
    return 1
  fi
  rm -f "$decoded_signature"
}

verify_pair "$(find_one '*.app.tar.gz')"
verify_pair "$(find_one '*.AppImage')"
verify_pair "$(find_one '*-setup.exe')"
verify_pair "$(find_one '*.msi')"

echo "Verified every desktop updater artifact against TAURI_UPDATER_PUBLIC_KEY."
