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

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
base="${1:?base image required, e.g. postgres:17-alpine}"

# Derive a valid tag from the base reference: drop any registry/namespace and
# digest, then prefix the repository name with dml-utils-.
base_name="${base##*/}"
base_name="${base_name%%@*}"
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

if [ -z "$name" ] || [ -z "$version" ]; then
    echo "could not derive a tag from base image: $base" >&2
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
