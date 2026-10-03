#!/bin/sh
# Reset the local development database: drop the data volume and bring the stack
# back up, so the migration re-applies the changelog to the fresh volume.
#
# Usage: scripts/refresh-local-db.sh

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

# This destroys the database volume, so make the target explicit and require an
# opt-in when not attached to a terminal (for example in a script).
echo "This deletes the PostgreSQL data volume for project '$project'." >&2
if [ ! -t 0 ] && [ "${REFRESH_LOCAL_DB_YES:-}" != "1" ]; then
    echo "refusing to run non-interactively without REFRESH_LOCAL_DB_YES=1" >&2
    exit 1
fi

# Pin the compose file and per-checkout project name so an inherited
# COMPOSE_FILE / project name cannot reset an unrelated stack. `down -v` drops
# this project's volume(s).
docker compose --project-name "$project" --file "$repo_root/compose.yaml" down -v

# Bring up PostgreSQL and apply the changelog, waiting for the migration to
# finish (the one-shot liquibase service has no healthcheck).
docker compose --project-name "$project" --file "$repo_root/compose.yaml" \
    up -d --wait postgres
docker compose --project-name "$project" --file "$repo_root/compose.yaml" \
    run --rm -T liquibase
