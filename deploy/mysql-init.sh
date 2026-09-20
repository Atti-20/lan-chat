#!/usr/bin/env bash
set -euo pipefail

# Executed by the official MySQL image; works with bind mounts and release archives.
# SQL session variables do not inherit Compose environment variables.
meshx_initialize_database() {
    local organization_key="${MESHX_ORGANIZATION_ID:-org-local}"
    if [[ ! "$organization_key" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,63}$ ]]; then
        echo 'MESHX_ORGANIZATION_ID must be 1-64 ASCII letters, digits, dots, underscores, colons or hyphens, starting with a letter or digit.' >&2
        return 1
    fi
    # The validated identifier cannot contain SQL quotes or escape sequences.
    MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}" \
        mysql --protocol=socket --user=root --default-character-set=utf8mb4 \
        --init-command="SET @meshx_organization_key = '$organization_key'" \
        < /opt/meshx/init.sql
}
meshx_initialize_database
unset -f meshx_initialize_database
