# dml_utils

DML helpers for PostgreSQL, packaged as Liquibase-managed SQL and applied with
the Liquibase CLI, without building anything or running Docker (see
[Installing with the Liquibase CLI](#installing-with-the-liquibase-cli)).

## What is in the box

Liquibase loads two application schemas:

- **`dml_utils`** — the application surface. It currently holds the `example`
  table loaded by the changelog, used as the starting point for the helpers.
- **`dml_utils_lib`** — generic helpers that take their parameters explicitly.
  Empty for now; it is the home for the shared helpers as they are added.

Liquibase's own tracking tables are kept out of both schemas: they live in a
dedicated `liquibase` schema as `liquibase.dml_utils_databasechangelog` and
`liquibase.dml_utils_databasechangeloglock`.

Every table carries `created_at`/`updated_at` (both `timestamptz NOT NULL
DEFAULT now()`), and the shared `dml_utils.set_updated_at()` trigger keeps
`updated_at` current on every `UPDATE`, so a caller cannot bypass it.

## Requirements

- PostgreSQL 16, 17 or 18 (CI builds and tests all three; 17 is the default).
- Java 25 and the Maven Wrapper (`./mvnw`) for the build and tests.
- Docker for the jOOQ codegen and the integration tests (Testcontainers).

## Building and testing

```sh
scripts/build-postgres-image.sh postgres:17-alpine   # once, or after docker/ changes
./mvnw verify
```

`verify` runs the unit tests, the container-backed integration tests, SQLFluff
over the changelog `.sql` files, and the Liquibase changelog linter. Docker must
be running.

- One test: `./mvnw -pl dml_utils -am test -Dtest=ExampleTableTest`
- Skip SQLFluff: `./mvnw verify -Dskip.sqlfluff`
- Skip the changelog linter: `./mvnw verify -Dskip.liquibase-linter`

## Local development database

```sh
scripts/build-postgres-image.sh postgres:17-alpine   # once
scripts/start-local-db.sh                            # up -d --wait
```

This starts a `dml-utils-postgres:17-alpine` container plus a one-shot Liquibase
service that applies the changelog as the `dml_utils_owner` role. Liquibase's
tracking tables live in the `liquibase` schema, created by the image's init
script. Connect with:

```sh
psql -h localhost -p 5433 -U postgres -d dml_utils   # password: postgres
```

The port is bound to `127.0.0.1` only and defaults to `5433` (so it can run
alongside the ddl_utils dev database on `5432`); the credentials are set in
`compose.yaml`. Data lives in the `dml_utils_pgdata` volume:
`scripts/stop-local-db.sh` keeps it, while `scripts/refresh-local-db.sh` wipes
it and re-runs the migrations.

## Installing with the Liquibase CLI

Installing dml_utils means applying the bundled Liquibase changelog to your
database. There is nothing to compile or package: clone the repository and point
the Liquibase CLI at `db.changelog-master.xml`.

### 1. Create the roles

Connect to the target database as a superuser (or a role that can `CREATE ROLE`)
and create the owner and caller roles:

```sql
CREATE ROLE dml_utils_owner LOGIN PASSWORD 'change-me';
CREATE ROLE dml_utils_caller; -- no login; the routines are granted to it

GRANT CREATE ON DATABASE mydb TO dml_utils_owner;
\connect mydb
GRANT CREATE ON SCHEMA public TO dml_utils_owner;

-- Liquibase keeps its tracking tables in a dedicated schema and does not create
-- the schema itself, so create it here, owned by the role that runs the
-- migration.
CREATE SCHEMA liquibase AUTHORIZATION dml_utils_owner;
```

`dml_utils_owner` needs `CREATE` on the database and schema so Liquibase can
create its tracking tables and the `dml_utils` / `dml_utils_lib` schemas. Never
run the migration as `postgres`.

### 2. Run the changelog

From the repository root, connect as the owner:

```sh
git clone https://github.com/eyupmiduck/dml_utils.git
cd dml_utils

liquibase \
  --url=jdbc:postgresql://localhost:5432/mydb \
  --username=dml_utils_owner \
  --password=change-me \
  --changelog-file=dml_utils/src/main/resources/db/changelog/db.changelog-master.xml \
  --liquibase-schema-name=liquibase \
  --database-changelog-table-name=dml_utils_databasechangelog \
  --database-changelog-lock-table-name=dml_utils_databasechangeloglock \
  update
```

`liquibase status` reports pending changesets; re-running `update` is safe
(Liquibase skips changesets it has already applied).

### Rolling back

Every changeset ships a rollback. Pass the same connection flags and replace
`update` with `rollback-count --count=<n>` to undo the last `n` changesets:

```sh
liquibase \
  --url=jdbc:postgresql://localhost:5432/mydb \
  --username=dml_utils_owner \
  --password=change-me \
  --changelog-file=dml_utils/src/main/resources/db/changelog/db.changelog-master.xml \
  --liquibase-schema-name=liquibase \
  --database-changelog-table-name=dml_utils_databasechangelog \
  --database-changelog-lock-table-name=dml_utils_databasechangeloglock \
  rollback-count --count=1
```

## Custom PostgreSQL image

The build and local dev database use a custom image
(`dml-utils-postgres:<ver>-alpine`) built from the official `postgres:<ver>-alpine`
image. It bakes in a roles init script (`docker/postgres/roles.sql`) that
creates the application roles and the `liquibase` schema before Liquibase runs:

- `dml_utils_owner` — owns the schemas and objects; Liquibase connects as this
  role (never as `postgres`).
- `dml_utils_caller` — the role privileges are granted to.
- `dml_utils_test` — granted `dml_utils_caller`; used by the integration tests.

The image also compiles the [`plpgsql_check`](https://github.com/okbob/plpgsql_check)
extension from source (pinned and checksum-verified), so it is available in dev
databases for static analysis of the routines.

The init script only runs on first initialization, so an existing data volume
keeps its roles and installed extensions as-is; recreate the volume
(`scripts/refresh-local-db.sh`) to pick up a new image.

## Repository layout

```
dml_utils/src/main/resources/db/changelog/   Liquibase changelog (master + changes)
dml_utils/src/test/java/io/github/eyupmiduck/dmlutils/   JUnit tests
docker/postgres/                             Custom image (roles + plpgsql_check)
scripts/                                     Local DB and release helpers
compose.yaml                                 Local development database
```

## License

See [LICENSE](LICENSE).
