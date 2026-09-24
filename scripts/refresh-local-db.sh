#!/bin/sh
# Reset the local development database: drop the data volume and bring the stack
# back up, so the one-shot Liquibase service re-applies the changelog to the
# fresh volume.
#
# Usage: scripts/refresh-local-db.sh

set -eu

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

cd "$repo_root"
docker compose down -v
docker compose up -d
