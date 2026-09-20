#!/usr/bin/env bash
set -euo pipefail

bundle_root="${1:?usage: notarize-macos-dmg.sh BUNDLE_ROOT}"
report_directory="${2:-release-verification/macos}"

: "${APPLE_ID:?APPLE_ID is required}"
: "${APPLE_PASSWORD:?APPLE_PASSWORD is required}"
: "${APPLE_TEAM_ID:?APPLE_TEAM_ID is required}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "DMG notarization must run on macOS." >&2
  exit 1
fi
if [[ ! -d "$bundle_root" ]]; then
  echo "Bundle root does not exist: ${bundle_root}" >&2
  exit 1
fi

dmg_count="$(
  find "$bundle_root" -type f -name '*.dmg' -print | awk 'END { print NR }'
)"
if [[ "$dmg_count" != "1" ]]; then
  echo "Expected exactly one DMG below ${bundle_root}; found ${dmg_count}." >&2
  find "$bundle_root" -type f -name '*.dmg' -print >&2
  exit 1
fi
dmg_path="$(find "$bundle_root" -type f -name '*.dmg' -print | head -n 1)"
mkdir -p "$report_directory"

if xcrun stapler validate -v "$dmg_path" \
  >"${report_directory}/dmg-stapler-preflight.txt" 2>&1; then
  echo "DMG already contains a valid notarization ticket: ${dmg_path}"
  exit 0
fi

submission_report="${report_directory}/dmg-notarytool-submit.json"
xcrun notarytool submit "$dmg_path" \
  --apple-id "$APPLE_ID" \
  --password "$APPLE_PASSWORD" \
  --team-id "$APPLE_TEAM_ID" \
  --wait \
  --output-format json >"$submission_report"

status="$(plutil -extract status raw -o - "$submission_report")"
submission_id="$(plutil -extract id raw -o - "$submission_report")"
if [[ "$status" != "Accepted" ]]; then
  xcrun notarytool log "$submission_id" \
    --apple-id "$APPLE_ID" \
    --password "$APPLE_PASSWORD" \
    --team-id "$APPLE_TEAM_ID" \
    >"${report_directory}/dmg-notarytool-log.json" || true
  echo "Apple notarization did not accept the DMG: ${status}" >&2
  exit 1
fi

xcrun stapler staple -v "$dmg_path" \
  2>&1 | tee "${report_directory}/dmg-stapler-staple.txt"
xcrun stapler validate -v "$dmg_path" \
  2>&1 | tee "${report_directory}/dmg-stapler-postflight.txt"

echo "Notarized and stapled DMG: ${dmg_path} (${submission_id})"
