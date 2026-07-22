#!/usr/bin/env bash
set -euo pipefail

image="${1:?usage: package-compose.sh IMAGE TAG OUTPUT_DIRECTORY}"
tag="${2:?usage: package-compose.sh IMAGE TAG OUTPUT_DIRECTORY}"
output_directory="${3:?usage: package-compose.sh IMAGE TAG OUTPUT_DIRECTORY}"

safe_tag="${tag//\//-}"
bundle_name="lanchat-compose-${safe_tag}"
staging_root="$(mktemp -d)"
staging_directory="${staging_root}/${bundle_name}"
trap 'rm -rf "$staging_root"' EXIT

mkdir -p "$staging_directory/deploy" "$staging_directory/sql" "$output_directory"
cp compose.yaml .env.example "$staging_directory/"
cp deploy/nginx.conf "$staging_directory/deploy/"
cp sql/init.sql sql/migration-*.sql "$staging_directory/sql/"

cat >"$staging_directory/compose.release.yaml" <<EOF
services:
  lanchat:
    image: ${image}:${tag}
    build: !reset null
    pull_policy: always
  lanchat-2:
    image: ${image}:${tag}
    build: !reset null
    pull_policy: always
EOF

cat >"$staging_directory/README.txt" <<EOF
LANChat server release ${tag}

1. Copy .env.example to .env and replace every blank secret.
2. Review migration scripts and back up existing data before upgrading.
3. Start the pinned release image:

   docker compose -f compose.yaml -f compose.release.yaml pull
   docker compose -f compose.yaml -f compose.release.yaml up -d

Image: ${image}:${tag}
EOF

archive="${output_directory}/${bundle_name}.tar.gz"
tar -C "$staging_root" -czf "$archive" "$bundle_name"
sha256sum "$archive" >"${archive}.sha256"
