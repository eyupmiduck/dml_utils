#!/bin/sh
# Cut a release by tagging the current commit and pushing the tag.
#
# Usage: scripts/cut-release.sh [--yes] <version>
#   e.g. scripts/cut-release.sh 0.1.0
#        scripts/cut-release.sh --yes 0.1.0
#
# The tag (v<version>) is the single source of truth for the release version:
# the Release workflow derives -Drevision from it, so the POM is not edited.
# The script refuses to run unless the working tree is clean, HEAD is an
# up-to-date main (so the release lands on a commit CI has built green), and
# the version is newer than the greatest existing tag. Pass --yes to skip the
# confirmation prompt for non-interactive use.

set -eu

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

usage() {
    cat <<'EOF'
Usage: scripts/cut-release.sh [--yes] <version>
   e.g. scripts/cut-release.sh 0.1.0
        scripts/cut-release.sh --yes 0.1.0

Tags the current commit v<version> and pushes the tag, which triggers the
Release workflow. The version is the tag name without the leading "v".
EOF
}

# Exit 0 when $1 (a v-prefixed version) is strictly greater than every existing
# v* tag, and print the greatest existing tag. The comparison is semver, so a
# release outranks its own prereleases (v0.2.0 > v0.2.0-rc1) and numbers are
# compared numerically (v0.10.0 > v0.9.0).
newest_tag() {
    git tag --list 'v*' | awk -v candidate="$1" '
        function parse(v,   n) {
            sub(/^v/, "", v)
            n = index(v, "-")
            if (n > 0) { CORE = substr(v, 1, n - 1); PRE = substr(v, n + 1) }
            else { CORE = v; PRE = "" }
        }
        function cmp_core(a, b,   x, y, i) {
            split(a, x, "\\."); split(b, y, "\\.")
            for (i = 1; i <= 3; i++)
                if (x[i] + 0 != y[i] + 0) return (x[i] + 0 > y[i] + 0) ? 1 : -1
            return 0
        }
        function cmp_pre(a, b,   x, y, n, m, i, xn, yn) {
            if (a == "" || b == "") {
                if (a == b) return 0
                return (a == "") ? 1 : -1
            }
            n = split(a, x, "\\."); m = split(b, y, "\\.")
            for (i = 1; i <= n && i <= m; i++) {
                xn = (x[i] ~ /^[0-9]+$/); yn = (y[i] ~ /^[0-9]+$/)
                if (xn && yn) {
                    if (x[i] + 0 != y[i] + 0) return (x[i] + 0 > y[i] + 0) ? 1 : -1
                } else if (xn != yn) {
                    return xn ? -1 : 1
                } else if (x[i] != y[i]) {
                    return (x[i] > y[i]) ? 1 : -1
                }
            }
            if (n != m) return (n > m) ? 1 : -1
            return 0
        }
        function semver_cmp(a, b,   r) {
            parse(a); ac = CORE; ap = PRE
            parse(b); bc = CORE; bp = PRE
            r = cmp_core(ac, bc)
            return (r != 0) ? r : cmp_pre(ap, bp)
        }
        { if (max == "" || semver_cmp($0, max) > 0) max = $0 }
        END {
            if (max == "" || semver_cmp(candidate, max) > 0) { print max; exit 0 }
            print max
            exit 1
        }
    '
}

yes=
version=
for arg in "$@"; do
    case "$arg" in
        -y | --yes)
            yes=1
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        -*)
            echo "unknown option: $arg" >&2
            usage >&2
            exit 1
            ;;
        *)
            if [ -n "$version" ]; then
                echo "unexpected extra argument: $arg" >&2
                usage >&2
                exit 1
            fi
            version="$arg"
            ;;
    esac
done

if [ -z "$version" ]; then
    echo "a version is required" >&2
    usage >&2
    exit 1
fi

# Accept either 0.1.0 or v0.1.0.
version="${version#v}"

if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'; then
    echo "invalid version '$version': expected <major>.<minor>.<patch> (optionally with a -prerelease)" >&2
    exit 1
fi

tag="v$version"

if ! command -v git >/dev/null 2>&1; then
    echo "git is required but was not found on PATH" >&2
    exit 1
fi

# The tag is the release version, so it must point at a pristine, up-to-date
# main: a dirty tree or a stale main could tag a commit CI has not built.
if [ -n "$(git status --porcelain)" ]; then
    echo "working tree is not clean; commit or stash your changes first" >&2
    git status --short >&2
    exit 1
fi

branch="$(git rev-parse --abbrev-ref HEAD)"
if [ "$branch" != "main" ]; then
    echo "releases must be cut from main (currently on '$branch')" >&2
    exit 1
fi

echo "Fetching origin..." >&2
git fetch --quiet origin main

head="$(git rev-parse HEAD)"
remote="$(git rev-parse origin/main)"
if [ "$head" != "$remote" ]; then
    echo "main is not up to date with origin/main; pull or push first" >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
    echo "tag $tag already exists locally" >&2
    exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    echo "tag $tag already exists on origin" >&2
    exit 1
fi

latest=""
if ! latest="$(newest_tag "$tag")"; then
    echo "version $version is not newer than the latest tag ${latest:-<none>}" >&2
    exit 1
fi

if [ -z "$yes" ]; then
    printf 'Tag %s at %s and push it? [y/N] ' "$tag" "$head"
    read -r reply || reply=
    case "$reply" in
        y | Y | yes | YES) ;;
        *)
            echo "aborted" >&2
            exit 1
            ;;
    esac
fi

git tag -a "$tag" -m "Release $tag"
if ! git push origin "$tag"; then
    echo "failed to push $tag; remove the local tag with: git tag -d $tag" >&2
    exit 1
fi

echo "Pushed $tag. The Release workflow will publish to GitHub Packages:" >&2
echo "  https://github.com/eyupmiduck/dml_utils/actions/workflows/release.yml" >&2
