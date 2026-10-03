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
# up-to-date main, and the version is newer than the greatest existing tag.
# Pass --yes to skip the confirmation prompt for non-interactive use.

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
# compared numerically (v0.10.0 > v0.9.0). Tags that are not valid SemVer are
# ignored (not compared), so a stray vfoo/v999/v1.2.3.foo cannot distort the
# ordering. Reads tags from stdin.
newest_tag() {
    awk -v candidate="$1" '
        # A valid release tag: v<major>.<minor>.<patch> with optional
        # -prerelease of dot-separated [0-9A-Za-z-] identifiers.
        function is_valid(v) {
            return v ~ /^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$/
        }
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
        { if (is_valid($0) && (max == "" || semver_cmp($0, max) > 0)) max = $0 }
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

case "$version" in
    *-*)
        semver_core="${version%%-*}"
        semver_pre="${version#*-}"
        has_pre=1
        ;;
    *)
        semver_core="$version"
        semver_pre=""
        has_pre=0
        ;;
esac

if ! printf '%s' "$semver_core" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "invalid version '$version': expected <major>.<minor>.<patch> (optionally with a -prerelease)" >&2
    exit 1
fi
# A numeric core identifier must not have a leading zero (SemVer 2.0), so the
# tag is canonical: 01.2.3 and 1.02.3 are rejected.
if printf '%s' "$semver_core" | grep -Eq '(^|\.)0[0-9]'; then
    echo "invalid version '$semver_core': core identifiers must not have leading zeros" >&2
    exit 1
fi

if [ "$has_pre" = "1" ]; then
    # SemVer 2.0 prerelease: dot-separated, non-empty [0-9A-Za-z-] identifiers,
    # and a numeric identifier must not have a leading zero.
    if ! printf '%s' "$semver_pre" | grep -Eq '^[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*$'; then
        echo "invalid prerelease '$semver_pre': empty or invalid identifier" >&2
        exit 1
    fi
    if printf '%s' "$semver_pre" | grep -Eq '(^|\.)0[0-9]'; then
        echo "invalid prerelease '$semver_pre': numeric identifiers must not have leading zeros" >&2
        exit 1
    fi
fi

tag="v$version"

if ! command -v git >/dev/null 2>&1; then
    echo "git is required but was not found on PATH" >&2
    exit 1
fi

# The tag is the release version, so it must point at a pristine, up-to-date
# main: tag only a commit that is on the released branch. (This does not verify
# CI status; rely on the protected-branch required checks for that.)
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

# Compare against the union of local and remote tags: `git fetch origin main`
# does not fetch tags unreachable from main, so a release tag on another commit
# would otherwise be missed and a non-newer version accepted.
latest=""
remote_tags="$(git ls-remote --tags --refs origin 'v*' | sed 's#.*refs/tags/##')"
if ! latest="$(printf '%s\n' "$remote_tags" | newest_tag "$tag")"; then
    # Fall back to local tags if the remote returned none (for example no
    # network or a fresh remote), so the ordering guard still applies.
    if ! latest="$(git tag --list 'v*' | newest_tag "$tag")"; then
        echo "version $version is not newer than the latest tag ${latest:-<none>}" >&2
        exit 1
    fi
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
    # A transport error can be ambiguous: the remote may have accepted the tag
    # before the connection failed. Remove the local tag only when the remote is
    # confirmed not to have it, and say so; otherwise keep it and tell the
    # operator the push may have succeeded.
    if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
        echo "push of $tag reported failure, but origin now has the tag; it likely succeeded" >&2
    else
        echo "failed to push $tag; origin does not have it, removing the local tag" >&2
        git tag -d "$tag" >&2
    fi
    exit 1
fi

echo "Pushed $tag. The Release workflow will publish to GitHub Packages:" >&2
echo "  https://github.com/eyupmiduck/dml_utils/actions/workflows/release.yml" >&2
