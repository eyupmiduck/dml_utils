#!/bin/sh
# Open a psql session against the benchmark database.
#
# Connects through `docker exec` to the dedicated benchmark container
# (BENCH_CONTAINER / BENCH_DB / BENCH_DB_USER from config.env), so no host psql
# or connection string is needed. Any arguments are passed to psql:
#
#   benchmark/psql.sh                       # interactive session
#   benchmark/psql.sh -c "SELECT 1"         # run one command
#   benchmark/psql.sh -f some.sql           # run a file
#
# Usage: benchmark/psql.sh [psql arguments...]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

if ! docker inspect "$BENCH_CONTAINER" >/dev/null 2>&1 \
    || [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "benchmark container $BENCH_CONTAINER is not running; run benchmark/setup.sh first" >&2
    exit 1
fi

# Allocate a TTY only when attached to one, so a piped invocation (for example
# `echo 'SELECT 1' | benchmark/psql.sh`) still works.
if [ -t 0 ]; then
    exec docker exec -it "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
else
    exec docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
fi
