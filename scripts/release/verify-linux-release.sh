#!/usr/bin/env bash
set -euo pipefail

bundle_root="${1:?usage: verify-linux-release.sh BUNDLE_ROOT VERSION}"
expected_version="${2:?usage: verify-linux-release.sh BUNDLE_ROOT VERSION}"
report_directory="${3:-release-verification/linux}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux release verification must run on Linux." >&2
  exit 1
fi
if [[ ! -d "$bundle_root" ]]; then
  echo "Bundle root does not exist: ${bundle_root}" >&2
  exit 1
fi

mkdir -p "$report_directory"

find_exactly_one() {
  local pattern="$1"
  local count
  count="$(find "$bundle_root" -type f -name "$pattern" -print | awk 'END { print NR }')"
  if [[ "$count" != "1" ]]; then
    echo "Expected exactly one ${pattern}; found ${count}." >&2
    find "$bundle_root" -type f -name "$pattern" -print >&2
    return 1
  fi
  find "$bundle_root" -type f -name "$pattern" -print | head -n 1
}

deb_path="$(find_exactly_one '*.deb')"
appimage_path="$(find_exactly_one '*.AppImage')"
signature_path="$(find_exactly_one '*.AppImage.sig')"

for artifact in "$deb_path" "$appimage_path" "$signature_path"; do
  if [[ ! -s "$artifact" ]]; then
    echo "Release artifact is empty: ${artifact}" >&2
    exit 1
  fi
done

package_version="$(dpkg-deb -f "$deb_path" Version)"
if [[ "$package_version" != "$expected_version" ]]; then
  echo "Debian package version ${package_version} does not match ${expected_version}." >&2
  exit 1
fi

file "$deb_path" "$appimage_path" \
  | tee "${report_directory}/file-types.txt"
dpkg-deb --info "$deb_path" >"${report_directory}/deb-info.txt"
sha256sum "$deb_path" "$appimage_path" "$signature_path" \
  | tee "${report_directory}/SHA256SUMS"

echo "Verified Linux packages and signed updater artifact."
