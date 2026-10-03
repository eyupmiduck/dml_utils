#!/bin/sh
# Build the custom PostgreSQL image with the application roles baked in.
#
# Usage: scripts/build-postgres-image.sh <base-image>
#   e.g. scripts/build-postgres-image.sh postgres:17-alpine
#   builds dml-utils-postgres:17-alpine
#
# The resulting image tag is what the `postgres.image` Maven property (and
# the compose POSTGRES_IMAGE variable) should point at.

set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: scripts/build-postgres-image.sh <base-image>" >&2
    exit 2
fi

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
base="$1"

# A digest-only reference cannot be turned into a stable, meaningful tag, so
# reject it rather than collapsing it to ...:latest.
case "$base" in
    *@*)
        echo "digest-pinned base images are not supported (pass a tag, e.g. postgres:17-alpine): $base" >&2
        exit 1
        ;;
esac

# Derive a valid tag from the base reference: drop any registry/namespace, then
# prefix the repository name with dml-utils-.
base_name="${base##*/}"
case "$base_name" in
    *:*)
        name="${base_name%:*}"
        version="${base_name##*:}"
        ;;
    *)
        name="$base_name"
        version="latest"
        ;;
esac

# Docker repository names must be lowercase.
name="$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')"

if ! printf '%s' "$name" | grep -Eq '^[a-z0-9]+([._-][a-z0-9]+)*$'; then
    echo "invalid image name derived from base image: $base" >&2
    exit 1
fi
# Docker tag: starts with a word character, then word/period/dash characters.
if ! printf '%s' "$version" | grep -Eq '^[A-Za-z0-9_][A-Za-z0-9_.-]*$'; then
    echo "invalid image tag derived from base image: $base" >&2
    exit 1
fi
tag="dml-utils-${name}:${version}"

context="$repo_root/docker/postgres"
if [ ! -d "$context" ]; then
    echo "build context not found: $context" >&2
    exit 1
fi

docker build -t "$tag" --build-arg BASE_IMAGE="$base" "$context"
echo "Built $tag"
