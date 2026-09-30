# Custom PostgreSQL image with the application roles and the plpgsql_check
# extension baked in.
#
# The roles init script runs on first container init (before Liquibase), so
# the dml_utils_owner / dml_utils_caller / dml_utils_test roles exist in any
# database created from this image.
#
# Build with: scripts/build-postgres-image.sh postgres:17-alpine
#
# BASE_IMAGE has no default so a missing --build-arg fails fast instead of
# silently building against a different PostgreSQL version than the build uses.
ARG BASE_IMAGE
FROM ${BASE_IMAGE}

# plpgsql_check is not packaged for the PostgreSQL build the official image
# installs (Alpine's package targets a different major), so build it from
# source against the image's own server headers. The PGXN distribution is
# immutable (unlike GitHub's regenerated tag archives); the version and its
# SHA-256 are both overridable build args, so they can be bumped together
# (PGXN publishes the digest under https://api.pgxn.org/dist/plpgsql_check/<version>/META.json).
# Bitcode generation is skipped (no LLVM in the image).
ARG PLPGSQL_CHECK_VERSION=2.10.10
ARG PLPGSQL_CHECK_SHA256=747e301cbe01b69940cd5df6503d562550d76b2b5b0301f71afffead50c75f6b
RUN apk add --no-cache --virtual .plpgsql-check-build build-base curl unzip \
    && curl -fsSL -o /tmp/plpgsql_check.zip \
        "https://api.pgxn.org/dist/plpgsql_check/${PLPGSQL_CHECK_VERSION}/plpgsql_check-${PLPGSQL_CHECK_VERSION}.zip" \
    && echo "${PLPGSQL_CHECK_SHA256}  /tmp/plpgsql_check.zip" | sha256sum -c - \
    && unzip -q /tmp/plpgsql_check.zip -d /tmp \
    && make -C "/tmp/plpgsql_check-${PLPGSQL_CHECK_VERSION}" USE_PGXS=1 with_llvm=no -j"$(nproc)" \
    && make -C "/tmp/plpgsql_check-${PLPGSQL_CHECK_VERSION}" USE_PGXS=1 with_llvm=no install \
    && apk del .plpgsql-check-build \
    && rm -rf /tmp/plpgsql_check*

# pg_background runs SQL in background workers (autonomous transactions); the
# dml_utils chunking routines will use it. It is not on PGXN, so the immutable
# GitHub release tarball is used, pinned by version and SHA-256 (both
# overridable build args). It builds with PGXS against the image's headers and
# does not need shared_preload_libraries. Unlike plpgsql_check it includes
# libpq-be.h, so it needs the OpenSSL and GSSAPI (krb5) headers to compile;
# bitcode generation is skipped (no LLVM in the image).
ARG PG_BACKGROUND_VERSION=2.0.4
ARG PG_BACKGROUND_SHA256=353db32a48661ff4c052cea454aea4791aa265425bf01b5ecc3f50e4990190d7
RUN apk add --no-cache --virtual .pg-background-build build-base curl openssl-dev krb5-dev \
    && curl -fsSL -o /tmp/pg_background.tar.gz \
        "https://github.com/vibhorkum/pg_background/archive/refs/tags/v${PG_BACKGROUND_VERSION}.tar.gz" \
    && echo "${PG_BACKGROUND_SHA256}  /tmp/pg_background.tar.gz" | sha256sum -c - \
    && mkdir -p /tmp/pg_background \
    && tar -xzf /tmp/pg_background.tar.gz -C /tmp/pg_background --strip-components=1 \
    && make -C /tmp/pg_background USE_PGXS=1 with_llvm=no -j"$(nproc)" \
    && make -C /tmp/pg_background USE_PGXS=1 with_llvm=no install \
    && apk del .pg-background-build \
    && rm -rf /tmp/pg_background*

# The entrypoint runs /docker-entrypoint-initdb.d scripts only when the data
# directory is initialized for the first time; a pre-existing volume is left
# untouched (and may lack the roles).
COPY --chown=postgres:postgres --chmod=0644 roles.sql /docker-entrypoint-initdb.d/roles.sql
