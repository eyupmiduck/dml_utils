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

# The entrypoint runs /docker-entrypoint-initdb.d scripts only when the data
# directory is initialized for the first time; a pre-existing volume is left
# untouched (and may lack the roles).
COPY --chown=postgres:postgres --chmod=0644 roles.sql /docker-entrypoint-initdb.d/roles.sql
