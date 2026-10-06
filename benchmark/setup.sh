#!/bin/sh
# Create (or refresh) the dedicated benchmark database.
#
# Starts a PostgreSQL container from the custom image with max_worker_processes
# raised enough for the thread sweep, applies the dml_utils changelog to it, then
# creates the deterministic synthetic source table and the throwaway target
# table. Safe to re-run; pass --refresh to drop the container and its data volume
# first (required after changing the server settings or the image).
#
# Usage: benchmark/setup.sh [--refresh]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/.." && pwd)"
. "$here/config.env"

usage() {
    echo "usage: $0 [--refresh]" >&2
}

refresh=0
for arg in "$@"; do
    case "$arg" in
        --refresh) refresh=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

# The custom image carries the roles, plpgsql_check and pg_background; build it
# if it is missing.
if ! docker image inspect "$BENCH_IMAGE" >/dev/null 2>&1; then
    echo "image $BENCH_IMAGE not found; building it ..." >&2
    "$repo_root/scripts/build-postgres-image.sh" "postgres:${BENCH_IMAGE##*:}"
fi

docker network inspect "$BENCH_NETWORK" >/dev/null 2>&1 \
    || docker network create "$BENCH_NETWORK" >/dev/null

if [ "$refresh" = 1 ]; then
    echo "removing container $BENCH_CONTAINER and volume $BENCH_VOLUME" >&2
    docker rm -f "$BENCH_CONTAINER" >/dev/null 2>&1 || true
    docker volume rm "$BENCH_VOLUME" >/dev/null 2>&1 || true
fi

if ! docker inspect "$BENCH_CONTAINER" >/dev/null 2>&1; then
    echo "starting $BENCH_CONTAINER ($BENCH_IMAGE) ..." >&2
    # The trailing `postgres -c ...` overrides the image CMD, so the server runs
    # with the settings a reproducible sweep needs.
    docker run -d --name "$BENCH_CONTAINER" --network "$BENCH_NETWORK" \
        -e POSTGRES_DB="$BENCH_DB" \
        -e POSTGRES_USER="$BENCH_DB_USER" \
        -e POSTGRES_PASSWORD="$BENCH_DB_USER" \
        -p "127.0.0.1:${BENCH_HOST_PORT}:5432" \
        -v "${BENCH_VOLUME}:/var/lib/postgresql/data" \
        "$BENCH_IMAGE" \
        postgres -c max_worker_processes="$BENCH_MAX_WORKER_PROCESSES" \
        -c shared_buffers="$BENCH_SHARED_BUFFERS" \
        -c max_connections="$BENCH_MAX_CONNECTIONS" >/dev/null
else
    docker start "$BENCH_CONTAINER" >/dev/null 2>&1 || true
fi

# Talk to the container through docker exec, so no host client is needed.
psql_run() {
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}
psql_stdin() {
    docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}

# The real server only listens on the default socket once its init scripts have
# run (the temporary init server uses a different socket), so a successful
# `SELECT 1` here means the container is ready.
echo "waiting for postgres ..." >&2
ready=0
for _ in $(seq 1 90); do
    if psql_run -At -c 'SELECT 1' >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
[ "$ready" = 1 ] || { echo "postgres did not become ready" >&2; exit 1; }

# Apply the changelog once (the routines are what we benchmark). The marker is
# the routine under test, so a re-run skips the driver download.
if ! psql_run -At -c "SELECT 1 FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'dml_utils' AND p.proname = 'run_migration_chunks'" \
        | grep -q 1; then
    echo "applying the changelog ..." >&2
    changelog_dir="$repo_root/$(basename "$repo_root")/src/main/resources/db/changelog"
    docker run --rm --network "$BENCH_NETWORK" \
        -v "$changelog_dir:/liquibase/changelog" \
        "$BENCH_LIQUIBASE_IMAGE" sh -c \
        "lpm add postgresql --global && liquibase \
            --url=jdbc:postgresql://${BENCH_CONTAINER}:5432/${BENCH_DB} \
            --username=${BENCH_OWNER_USER} \
            --password=${BENCH_OWNER_PASSWORD} \
            --changelog-file=changelog/db.changelog-master.xml \
            --liquibase-schema-name=liquibase \
            --database-changelog-table-name=${BENCH_DB}_databasechangelog \
            --database-changelog-lock-table-name=${BENCH_DB}_databasechangeloglock \
            update"
fi

# Deterministic source data, then the throwaway target.
echo "creating benchmark tables ($BENCH_ROWS source rows) ..." >&2
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v source_table="$BENCH_SOURCE_TABLE" \
    -v rows="$BENCH_ROWS" \
    -f - < "$here/sql/create-source.sql"
psql_stdin -v ON_ERROR_STOP=1 \
    -v schema="$BENCH_SCHEMA" \
    -v target_table="$BENCH_TARGET_TABLE" \
    -f - < "$here/sql/create-target.sql"

echo "benchmark database ready: $BENCH_CONTAINER / $BENCH_DB" >&2
