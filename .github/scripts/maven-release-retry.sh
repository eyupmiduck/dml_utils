#!/usr/bin/env bash
# Run the Maven release build, retrying once on a Testcontainers/PostgreSQL
# startup race.
#
# testcontainers-jooq-codegen-maven-plugin:0.0.4 overrides the PostgreSQL
# container's readiness wait with HostPortWaitStrategy, which returns as soon as
# the port is open. On a loaded CI runner jOOQ can then connect while Postgres is
# still initialising and the release fails with
#
#   FATAL: the database system is starting up
#
# There is no configuration to change the wait strategy and no newer plugin
# release (the fix exists only on the plugin's main branch). Retry the release
# once when that specific transient failure (or the equivalent port-wait
# timeout) occurs; any other failure is reported unchanged.
#
# deployAtEnd is important here: without it Maven publishes the parent POM
# during the parent module's deploy phase, before the dml_utils module has built.
# A codegen failure then leaves an orphan package version, and the retry fails
# with HTTP 409 because GitHub Packages does not allow replacing a version.
# deployAtEnd defers publication until the whole reactor has built.
#
# Usage: .github/scripts/maven-release-retry.sh [maven args...]
# The reusable release workflow appends -Drevision=... and -Dpostgres.image=...,
# which are forwarded to ./mvnw like any other argument.
set -euo pipefail

log="$(mktemp)"
trap 'rm -f "$log"' EXIT

run_build() {
    ./mvnw --batch-mode -s .github/scripts/settings.xml clean deploy \
        -DdeployAtEnd=true "$@"
}

if run_build "$@" 2>&1 | tee "$log"; then
    exit 0
fi

if grep -Eq 'the database system is starting up|Timed out waiting for container port' "$log"; then
    printf '\nTransient PostgreSQL container startup race; retrying the release once.\n' >&2
    run_build "$@"
else
    if grep -q 'HTTP Status: 409' "$log"; then
        printf '\nA package version already exists; GitHub Packages cannot replace it.\n' >&2
        printf 'Delete the partial version or release a new version.\n' >&2
    fi
    exit 1
fi
