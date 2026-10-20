#!/usr/bin/env bash
# Run the Maven build, retrying once on a Testcontainers/PostgreSQL startup race.
#
# testcontainers-jooq-codegen-maven-plugin:0.0.4 overrides the PostgreSQL
# container's readiness wait with HostPortWaitStrategy, which returns as soon as
# the port is open. On a loaded CI runner jOOQ can then connect while Postgres is
# still initialising and the codegen fails with
#
#   FATAL: the database system is starting up
#
# There is no configuration to change the wait strategy and no newer plugin
# release (the fix exists only on the plugin's main branch). Retry the build once
# when that specific transient failure (or the equivalent port-wait timeout)
# occurs; any other failure is reported unchanged.
#
# Usage: .github/scripts/maven-verify-retry.sh [maven args...]
# The reusable Maven workflow appends -Dpostgres.image=..., which is forwarded
# to ./mvnw like any other argument.
set -euo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

run_build() {
    ./mvnw --batch-mode -s .github/scripts/settings.xml clean verify "$@"
}

if run_build "$@" 2>&1 | tee "$log"; then
    exit 0
fi

if grep -Eq 'the database system is starting up|Timed out waiting for container port' "$log"; then
    printf '\nTransient PostgreSQL container startup race; retrying the build once.\n' >&2
    run_build "$@"
else
    exit 1
fi
