#!/bin/sh
# Auto-fix SQL style issues in the Liquibase changelog with SQLFluff.
#
# Usage: scripts/sqlfluff-fix.sh
#
# Uses the repo's .venv sqlfluff. Falling back to `sqlfluff` on PATH is an
# explicit opt-in (SQLFLUFF_FIX_ALLOW_PATH=1) because this command rewrites
# source files. Only fixes what SQLFluff can fix automatically; remaining
# violations are reported and must be fixed by hand.

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
elif [ "${SQLFLUFF_FIX_ALLOW_PATH:-}" = "1" ] && command -v sqlfluff >/dev/null 2>&1; then
    sqlfluff="sqlfluff"
    echo "warning: .venv sqlfluff not found; using '$sqlfluff' from PATH" >&2
else
    echo "sqlfluff not found at $repo_root/.venv/bin/sqlfluff; create the repo .venv," >&2
    echo "or set SQLFLUFF_FIX_ALLOW_PATH=1 to use sqlfluff from PATH" >&2
    exit 1
fi

# Run from the repo root so SQLFluff discovers the repository .sqlfluff rather
# than config from the caller's working directory.
cd "$repo_root"
"$sqlfluff" fix "$sql_dir"
