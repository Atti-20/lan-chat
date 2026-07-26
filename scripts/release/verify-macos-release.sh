#!/usr/bin/env bash
set -euo pipefail

bundle_root="${1:?usage: verify-macos-release.sh BUNDLE_ROOT}"
expected_version="${2:?usage: verify-macos-release.sh BUNDLE_ROOT VERSION}"
report_directory="${3:-release-verification/macos}"

: "${APPLE_SIGNING_IDENTITY:?APPLE_SIGNING_IDENTITY is required}"
: "${APPLE_TEAM_ID:?APPLE_TEAM_ID is required}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS release verification must run on macOS." >&2
  exit 1
fi
if [[ ! -d "$bundle_root" ]]; then
  echo "Bundle root does not exist: ${bundle_root}" >&2
  exit 1
fi

mkdir -p "$report_directory"

find_one() {
  local root="$1"
  local type="$2"
  local pattern="$3"
  local result_count
  local result

  result_count="$(
    find "$root" -type "$type" -name "$pattern" -print \
      | awk 'END { print NR }'
  )"
  if [[ "$result_count" != "1" ]]; then
    echo "Expected exactly one ${pattern} below ${root}; found ${result_count}." >&2
    find "$root" -type "$type" -name "$pattern" -print >&2
    return 1
  fi

  result="$(find "$root" -type "$type" -name "$pattern" -print | head -n 1)"
  printf '%s\n' "$result"
}

verify_app() {
  local app_path="$1"
  local label="$2"
  local plist="${app_path}/Contents/Info.plist"
  local executable_name
  local executable_path
  local bundle_version
  local architectures
  local signature_report="${report_directory}/${label}-codesign.txt"

  if [[ ! -f "$plist" ]]; then
    echo "Missing Info.plist: ${plist}" >&2
    return 1
  fi

  executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$plist")"
  bundle_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$plist")"
  executable_path="${app_path}/Contents/MacOS/${executable_name}"

  if [[ "$bundle_version" != "$expected_version" ]]; then
    echo "${label} version ${bundle_version} does not match ${expected_version}." >&2
    return 1
  fi
  if [[ ! -x "$executable_path" ]]; then
    echo "Missing executable: ${executable_path}" >&2
    return 1
  fi

  lipo -verify_arch arm64 x86_64 "$executable_path"
  architectures="$(lipo -archs "$executable_path")"
  printf '%s\n' "$architectures" >"${report_directory}/${label}-architectures.txt"
  for required_architecture in arm64 x86_64; do
    if [[ " ${architectures} " != *" ${required_architecture} "* ]]; then
      echo "${label} is missing ${required_architecture}: ${architectures}" >&2
      return 1
    fi
  done

  codesign --verify --deep --strict --verbose=4 "$app_path"
  codesign -dv --verbose=4 "$app_path" 2>&1 | tee "$signature_report"

  if grep -Fq 'Signature=adhoc' "$signature_report"; then
    echo "${label} uses an ad-hoc signature." >&2
    return 1
  fi
  grep -Eq '^Authority=Developer ID Application: .+ \([A-Z0-9]+\)$' \
    "$signature_report"
  grep -Fqx "TeamIdentifier=${APPLE_TEAM_ID}" "$signature_report"
  grep -Eq '^Timestamp=.+$' "$signature_report"

  spctl --assess --type execute --verbose=4 "$app_path" \
    2>&1 | tee "${report_directory}/${label}-gatekeeper.txt"
  xcrun stapler validate -v "$app_path" \
    2>&1 | tee "${report_directory}/${label}-stapler.txt"
}

app_path="$(find_one "$bundle_root" d '*.app')"
dmg_path="$(find_one "$bundle_root" f '*.dmg')"
mounted_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/meshx-release-mount.XXXXXX")"
mounted=false

cleanup() {
  if [[ "$mounted" == true ]]; then
    hdiutil detach "$mounted_directory" -quiet || true
  fi
  rmdir "$mounted_directory" 2>/dev/null || true
}
trap cleanup EXIT

verify_app "$app_path" "built-app"

codesign --verify --strict --verbose=4 "$dmg_path"
codesign -dv --verbose=4 "$dmg_path" 2>&1 \
  | tee "${report_directory}/dmg-codesign.txt"
grep -Eq '^Authority=Developer ID Application: .+ \([A-Z0-9]+\)$' \
  "${report_directory}/dmg-codesign.txt"
grep -Fqx "TeamIdentifier=${APPLE_TEAM_ID}" \
  "${report_directory}/dmg-codesign.txt"
if grep -Fq 'Signature=adhoc' "${report_directory}/dmg-codesign.txt"; then
  echo "DMG uses an ad-hoc signature." >&2
  exit 1
fi

hdiutil verify "$dmg_path" 2>&1 | tee "${report_directory}/dmg-verify.txt"
spctl --assess --type open --context context:primary-signature \
  --verbose=4 "$dmg_path" 2>&1 | tee "${report_directory}/dmg-gatekeeper.txt"
xcrun stapler validate -v "$dmg_path" \
  2>&1 | tee "${report_directory}/dmg-stapler.txt"

hdiutil attach "$dmg_path" -readonly -nobrowse -mountpoint "$mounted_directory" \
  2>&1 | tee "${report_directory}/dmg-mount.txt"
mounted=true

mounted_app="$(find_one "$mounted_directory" d '*.app')"
verify_app "$mounted_app" "mounted-app"

built_executable="$(
  /usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' \
    "${app_path}/Contents/Info.plist"
)"
mounted_executable="$(
  /usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' \
    "${mounted_app}/Contents/Info.plist"
)"
shasum -a 256 \
  "${app_path}/Contents/MacOS/${built_executable}" \
  "${mounted_app}/Contents/MacOS/${mounted_executable}" \
  "$dmg_path" | tee "${report_directory}/SHA256SUMS"

built_hash="$(
  shasum -a 256 "${app_path}/Contents/MacOS/${built_executable}" \
    | awk '{ print $1 }'
)"
mounted_hash="$(
  shasum -a 256 "${mounted_app}/Contents/MacOS/${mounted_executable}" \
    | awk '{ print $1 }'
)"
if [[ "$built_hash" != "$mounted_hash" ]]; then
  echo "Mounted application binary does not match the built application." >&2
  exit 1
fi

echo "Verified Developer ID, notarized universal app and DMG: ${dmg_path}"
