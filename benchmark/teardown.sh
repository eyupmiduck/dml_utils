#!/bin/sh
# Remove the benchmark container, its data volume and its network, reclaiming the
# disk space. Results on the host (benchmark/results/) are not touched. Recreate
# the database later with benchmark/setup.sh.
#
# Usage: benchmark/teardown.sh

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"
# Optional personal overrides, kept out of git (copy config.local.env.example).
if [ -f "$here/config.local.env" ]; then
    . "$here/config.local.env"
fi

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

# Best effort: each may already be absent.
docker rm -f "$BENCH_CONTAINER" >/dev/null 2>&1 || true
docker volume rm "$BENCH_VOLUME" >/dev/null 2>&1 || true
docker network rm "$BENCH_NETWORK" >/dev/null 2>&1 || true

echo "removed container $BENCH_CONTAINER, volume $BENCH_VOLUME and network $BENCH_NETWORK" >&2
