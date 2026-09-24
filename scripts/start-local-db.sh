#!/bin/sh
# Start the local development database.
#
# Usage: scripts/start-local-db.sh
#
# This starts the PostgreSQL container plus the one-shot `liquibase` service
# that applies the changelog, and waits for the services to be healthy.

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

docker compose up -d --wait
