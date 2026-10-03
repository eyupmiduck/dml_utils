#!/bin/sh
# Start the local development database.
#
# Usage: scripts/start-local-db.sh
#
# This starts the PostgreSQL container and applies the changelog, waiting for
# the migration to finish successfully before returning.

set -eu

script_path="$0"
if command -v readlink >/dev/null 2>&1; then
    resolved="$(readlink -f "$script_path" 2>/dev/null || true)"
    [ -n "$resolved" ] && script_path="$resolved"
fi
repo_root="$(cd "$(dirname "$script_path")/.." && pwd)"

cd "$repo_root"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required but was not found on PATH" >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "docker compose (v2) is required but is not available" >&2
    exit 1
fi

# Pin the compose file and project so an inherited COMPOSE_FILE / project name
# cannot make this act on an unrelated stack. Bring up PostgreSQL and wait for
# its healthcheck.
docker compose --project-name dml_utils --file "$repo_root/compose.yaml" \
    up -d --wait postgres

# The liquibase service is one-shot with no healthcheck, so `up --wait` cannot
# wait for the migration. Run it explicitly and propagate its exit status.
docker compose --project-name dml_utils --file "$repo_root/compose.yaml" \
    run --rm -T liquibase
