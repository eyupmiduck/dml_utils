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
elif [ "${SQLFLUFF_FIX_ALLOW_PATH:-}" = "1" ]; then
    # Resolve the PATH candidate to an absolute regular file before any `cd`, so
    # a relative PATH entry cannot point at one executable now and another after
    # the directory change.
    resolved="$(command -v sqlfluff 2>/dev/null || true)"
    case "$resolved" in
        /*) sqlfluff="$resolved" ;;
        "")
            echo "sqlfluff not found on PATH" >&2
            exit 1
            ;;
        *)
            # command -v printed a relative path; anchor it to the current dir.
            sqlfluff="$(cd "$(dirname "$resolved")" && pwd)/$(basename "$resolved")"
            ;;
    esac
    if [ ! -f "$sqlfluff" ] || [ ! -x "$sqlfluff" ]; then
        echo "resolved sqlfluff is not an executable file: $sqlfluff" >&2
        exit 1
    fi
    echo "warning: .venv sqlfluff not found; using '$sqlfluff' from PATH" >&2
else
    echo "sqlfluff not found at $repo_root/.venv/bin/sqlfluff; create the repo .venv," >&2
    echo "or set SQLFLUFF_FIX_ALLOW_PATH=1 to use sqlfluff from PATH" >&2
    exit 1
fi

# Run from the repo root so SQLFluff discovers the repository .sqlfluff rather
# than config from the caller's working directory.
cd "$repo_root"

# Report which files SQLFluff will change before changing them, so a broadened
# or misconfigured include rule is visible rather than silently rewriting the
# tree.
echo "SQLFluff will fix files under: $sql_dir" >&2
changed="$("$sqlfluff" fix --dry-run "$sql_dir" 2>/dev/null | sed -n 's/^== \[\(.*\)\] FAIL.*/\1/p' || true)"
if [ -n "$changed" ]; then
    echo "Files with fixable violations:" >&2
    printf '  %s\n' "$changed" >&2
fi

"$sqlfluff" fix "$sql_dir"
