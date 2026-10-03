#!/bin/sh
# Reset the local development database: drop the data volume and bring the stack
# back up, so the one-shot Liquibase service re-applies the changelog to the
# fresh volume.
#
# Usage: scripts/refresh-local-db.sh

set -eu

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

cd "$repo_root"

# Pin the compose file and project so an inherited COMPOSE_FILE / project name
# cannot reset an unrelated stack. `down -v` drops the project's volume(s).
docker compose --project-name dml_utils --file "$repo_root/compose.yaml" down -v

# Bring up PostgreSQL and apply the changelog, waiting for the migration to
# finish (the one-shot liquibase service has no healthcheck).
docker compose --project-name dml_utils --file "$repo_root/compose.yaml" \
    up -d --wait postgres
docker compose --project-name dml_utils --file "$repo_root/compose.yaml" \
    run --rm -T liquibase
