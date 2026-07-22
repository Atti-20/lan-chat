#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:-output/playwright}"
mkdir -p "$output_dir"

docker compose -f compose.yaml -f compose.e2e.yaml ps --all \
  >"$output_dir/compose-ps.txt" 2>&1 || true
docker compose -f compose.yaml -f compose.e2e.yaml logs \
  --no-color --timestamps >"$output_dir/compose.log" 2>&1 || true

mapfile -t application_containers < <(
  docker compose -f compose.yaml -f compose.e2e.yaml \
    ps -q lanchat lanchat-2 2>/dev/null
)
if (( ${#application_containers[@]} > 0 )); then
  docker inspect "${application_containers[@]}" \
    >"$output_dir/application-inspect.json" 2>&1 || true
fi
