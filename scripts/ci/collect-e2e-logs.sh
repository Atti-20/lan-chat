#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:-output/playwright}"
mkdir -p "$output_dir"

docker compose -f compose.yaml -f compose.e2e.yaml ps --all \
  >"$output_dir/compose-ps.txt" 2>&1 || true
docker compose -f compose.yaml -f compose.e2e.yaml logs \
  --no-color --timestamps >"$output_dir/compose.log" 2>&1 || true

docker compose -f compose.yaml -f compose.e2e.yaml config \
  >"$output_dir/compose-config-final.yaml" 2>&1 || true

mapfile -t failed_containers < <(
  docker compose -f compose.yaml -f compose.e2e.yaml ps -aq 2>/dev/null \
    | while IFS= read -r container_id; do
        [[ -n "$container_id" ]] || continue
        state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
          "$container_id" 2>/dev/null || true)"
        if [[ "$state" =~ ^(created|exited|dead|restarting|removing)($|[[:space:]]) ]] \
          || [[ "$state" == *" unhealthy" ]]; then
          echo "$container_id"
        fi
      done
)
if (( ${#failed_containers[@]} > 0 )); then
  docker inspect "${failed_containers[@]}" \
    >"$output_dir/failed-container-inspect.json" 2>&1 || true
else
  printf '[]\n' >"$output_dir/failed-container-inspect.json"
fi
