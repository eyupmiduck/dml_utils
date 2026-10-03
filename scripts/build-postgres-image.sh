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

# Split "<registry>/<namespace>/.../<name>:<tag>". Keep the last path component
# as the name and the part before it (if any) as a sanitized namespace, so
# registry-a/postgres:17 and registry-b/postgres:17 do not collapse to the same
# local tag.
base_ref="${base}"
case "$base_ref" in
    */*)
        namespace="${base_ref%/*}"
        last="${base_ref##*/}"
        ;;
    *)
        namespace=""
        last="$base_ref"
        ;;
esac
case "$last" in
    *:*)
        name="${last%:*}"
        version="${last##*:}"
        ;;
    *)
        name="$last"
        version="latest"
        ;;
esac

# Docker repository names must be lowercase and valid.
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

# A namespace/registry only contributes a sanitized prefix; the digest suffix
# makes it unique even when the sanitized form collides.
ns_prefix=""
if [ -n "$namespace" ]; then
    ns_sanitized="$(printf '%s' "$namespace" | tr '[:upper:]' '[:lower:]' | sed 's#[^a-z0-9._-]#-#g')"
    ns_prefix="$(printf '%s' "$ns_sanitized" | cut -c1-20)-"
fi

tag="dml-utils-${ns_prefix}${name}:${version}"

# Docker limits: repository name <= 255 chars, tag <= 128 chars.
repo_name="${tag%%:*}"
if [ "${#repo_name}" -gt 255 ]; then
    echo "derived repository name exceeds Docker's 255-character limit: $repo_name" >&2
    exit 1
fi
if [ "${#version}" -gt 128 ]; then
    echo "derived tag exceeds Docker's 128-character limit: $version" >&2
    exit 1
fi

context="$repo_root/docker/postgres"
if [ ! -d "$context" ]; then
    echo "build context not found: $context" >&2
    exit 1
fi

docker build -t "$tag" --build-arg BASE_IMAGE="$base" "$context"
echo "Built $tag"
