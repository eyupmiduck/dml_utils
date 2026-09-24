#!/bin/sh
# Auto-fix SQL style issues in the Liquibase changelog with SQLFluff.
#
# Usage: scripts/sqlfluff-fix.sh
#
# Uses the repo's .venv sqlfluff if present, otherwise falls back to
# `sqlfluff` on PATH. Only fixes what SQLFluff can fix automatically;
# remaining violations are reported and must be fixed by hand.

set -eu

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
sql_dir="$repo_root/dml_utils/src/main/resources/db/changelog"

if [ ! -d "$sql_dir" ]; then
    echo "SQL changelog directory not found: $sql_dir" >&2
    exit 1
fi

if [ ! -f "$repo_root/.sqlfluff" ]; then
    echo "SQLFluff config not found: $repo_root/.sqlfluff" >&2
    exit 1
fi

if [ -x "$repo_root/.venv/bin/sqlfluff" ]; then
    sqlfluff="$repo_root/.venv/bin/sqlfluff"
elif command -v sqlfluff >/dev/null 2>&1; then
    sqlfluff="sqlfluff"
    echo "warning: .venv sqlfluff not found; using '$sqlfluff' from PATH" >&2
else
    echo "sqlfluff not found: create the repo .venv or install sqlfluff on PATH" >&2
    exit 1
fi

"$sqlfluff" fix "$sql_dir"
