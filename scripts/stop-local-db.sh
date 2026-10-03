#!/bin/sh
# Stop the local development database, keeping its data volume.
#
# Usage: scripts/stop-local-db.sh

set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"
. "$script_dir/lib.sh"

repo_root="$(resolve_repo_root "$0")"
project="$(compose_project_name "$repo_root")"

cd "$repo_root"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required but was not found on PATH" >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose (v2) is required but is not available" >&2
    exit 1
fi

# `down` keeps named volumes by default; do not add -v here. Use
# scripts/refresh-local-db.sh, which wipes the data on purpose. Pin the compose
# file and per-checkout project name so an inherited COMPOSE_FILE / project name
# cannot bring down an unrelated stack.
docker compose --project-name "$project" --file "$repo_root/compose.yaml" down
