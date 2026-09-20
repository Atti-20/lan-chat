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
cp deploy/nginx.conf deploy/mysql-init.sh "$staging_directory/deploy/"
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

Before either a fresh install or an upgrade, validate the merged configuration:

   docker compose -f compose.yaml -f compose.release.yaml config --quiet

For a fresh install, copy .env.example to .env and replace every blank secret.
For an upgrade, keep the existing .env and merge only newly introduced keys
from .env.example. Never overwrite an installation's existing secrets.

Existing installation upgrade
=============================

Do not run sql/init.sql against an existing database. It recreates tables.
The migration files are not a migration ledger, and V2.4 is not repeatable.
Use the version/schema records from the existing installation to identify the
first missing migration, then run ONLY missing migrations in the order listed
below. If that boundary is uncertain, stop and inspect information_schema
before applying SQL.

1. Pull the release image, then stop both application instances and the gateway.
   Keep MySQL, Redis, and MinIO running:

   docker compose -f compose.yaml -f compose.release.yaml pull
   docker compose -f compose.yaml -f compose.release.yaml stop gateway lanchat lanchat-2
   docker compose -f compose.yaml -f compose.release.yaml up -d mysql redis minio

2. Back up the database before changing its schema. Also snapshot the
   mysql-data, minio-data, and uploads volumes with the installation's normal
   backup tooling:

   docker compose -f compose.yaml -f compose.release.yaml exec -T mysql sh -c \\
     'exec mysqldump --user=root --password="\$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --events lan_chat' \\
     > lan_chat-before-${safe_tag}.sql
   test -s lan_chat-before-${safe_tag}.sql

3. Define this helper, remove every already-applied line from the ordered list,
   and run only the remaining migrations:

   apply_migration() {
     docker compose -f compose.yaml -f compose.release.yaml exec -T mysql sh -c \\
       'exec mysql --user=root --password="\$MYSQL_ROOT_PASSWORD" lan_chat' < "\$1"
   }

   apply_migration sql/migration-v1.1-file-access-cache.sql
   apply_migration sql/migration-v2.0-reliable-messaging.sql
   apply_migration sql/migration-v2.1-security-diagnostics.sql
   apply_migration sql/migration-v2.2-file-transfer.sql
   apply_migration sql/migration-v2.2-temporary-rooms.sql
   apply_migration sql/migration-v2.2-emergency-broadcast.sql
   apply_migration sql/migration-v2.2-broadcast-permission.sql
   apply_migration sql/migration-v2.3-resumable-object-storage.sql
   apply_migration sql/migration-v2.4-broadcast-task-workflow.sql
   apply_migration sql/migration-v2.5-user-lifecycle.sql
   apply_migration sql/migration-v2.6-device-session-single-active.sql
   # V2.7 must use the same organization key as the application. Replace the
   # example below with MESHX_ORGANIZATION_ID from this installation's .env.
   # Run both statements in ONE mysql session, and only if V2.7 is missing.
   { echo "SET @meshx_organization_key = 'org-private';"; cat sql/migration-v2.7-control-organization-rbac.sql; } | \\
     docker compose -f compose.yaml -f compose.release.yaml exec -T mysql sh -c \\
       'exec mysql --user=root --password="\$MYSQL_ROOT_PASSWORD" lan_chat'
   apply_migration sql/migration-v2.8-device-identity.sql

4. Inspect the release-critical V2.4-V2.8 schema before restarting. Check the
   expected organization key, owner membership, device tables and unique indexes:

   docker compose -f compose.yaml -f compose.release.yaml exec -T mysql sh -c \\
     'exec mysql --user=root --password="\$MYSQL_ROOT_PASSWORD" --table lan_chat' <<'SQL'
   SHOW TABLES LIKE 'broadcast_evidence';
   SHOW TABLES LIKE 'admin_user_lifecycle_audit';
   SHOW COLUMNS FROM broadcast LIKE 'require_image_proof';
   SHOW COLUMNS FROM user LIKE 'archived_at';
   SHOW COLUMNS FROM device_login LIKE 'active_device_type';
   SELECT organization_key FROM organization;
   SHOW TABLES LIKE 'audit_event';
   SHOW COLUMNS FROM device_credential LIKE 'certificate_payload';
   SHOW COLUMNS FROM device_session LIKE 'legacy_device_login_id';
   SHOW COLUMNS FROM organization_policy LIKE 'revocation_version';
   SELECT DISTINCT index_name, non_unique
     FROM information_schema.statistics
    WHERE table_schema = 'lan_chat'
      AND table_name = 'device_login'
      AND index_name = 'uk_device_active_type';
SQL

5. Start the pinned release, wait for Compose health checks, and verify the
   gateway health endpoint from inside the container:

   docker compose -f compose.yaml -f compose.release.yaml up -d --wait --wait-timeout 300
   docker compose -f compose.yaml -f compose.release.yaml ps
   docker compose -f compose.yaml -f compose.release.yaml exec -T gateway \\
     wget -qO- http://127.0.0.1:8080/api/v1/node/health

Fresh installation
==================

With a new empty mysql-data volume, init.sql is applied automatically:

   docker compose -f compose.yaml -f compose.release.yaml pull
   docker compose -f compose.yaml -f compose.release.yaml up -d --wait --wait-timeout 300
   docker compose -f compose.yaml -f compose.release.yaml ps
   docker compose -f compose.yaml -f compose.release.yaml exec -T gateway \\
     wget -qO- http://127.0.0.1:8080/api/v1/node/health

Image: ${image}:${tag}
EOF

archive="${output_directory}/${bundle_name}.tar.gz"
tar -C "$staging_root" -czf "$archive" "$bundle_name"
sha256sum "$archive" >"${archive}.sha256"
