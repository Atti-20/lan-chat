#!/usr/bin/env bash
set -euo pipefail

release_tag="${1:?usage: verify-release-tag.sh vMAJOR.MINOR.PATCH}"
version="$(tr -d '\r\n' < VERSION)"
expected_tag="v${version}"

if [[ "$release_tag" != "$expected_tag" ]]; then
  echo "Release tag ${release_tag} does not match VERSION (${expected_tag})." >&2
  exit 1
fi

tag_commit="$(git rev-parse --verify "${release_tag}^{commit}" 2>/dev/null)" || {
  echo "Release tag ${release_tag} is not available in this checkout." >&2
  exit 1
}
head_commit="$(git rev-parse --verify HEAD)"

if [[ "$tag_commit" != "$head_commit" ]]; then
  echo "Release tag ${release_tag} points to ${tag_commit}, not checked-out ${head_commit}." >&2
  exit 1
fi

python_command=""
for candidate in python python3; do
  if command -v "$candidate" >/dev/null 2>&1 \
    && "$candidate" -c 'import sys; raise SystemExit(sys.version_info < (3, 9))' \
      >/dev/null 2>&1; then
    python_command="$candidate"
    break
  fi
done
if [[ -z "$python_command" ]]; then
  echo "Python 3.9 or newer is required to verify release versions." >&2
  exit 1
fi

"$python_command" scripts/ci/check-version-consistency.py
echo "Release tag verified: ${release_tag} -> ${head_commit}"
