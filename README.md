# dml_utils

DML helpers for PostgreSQL, packaged as Liquibase-managed SQL and applied with
the Liquibase CLI, without building anything or running Docker (see
[Installing with the Liquibase CLI](#installing-with-the-liquibase-cli)).

## What is in the box

Liquibase loads three application schemas, layered so nothing lower depends on
anything above it:

- **`dml_utils`** — the caller-facing API: `dml_utils.run_migration_chunks`, plus
  `set_migration_run_sql_text` and `archive_migration_run`. It depends on the
  two schemas below.
- **`dml_utils_lib`** — the engine: the generic catalog and template helpers and
  the internal routines that populate boundaries, run one chunk and record
  errors. It may use `dml_utils_data`, never `dml_utils`.
- **`dml_utils_data`** — the data layer: the shared domains (`non_null_text`,
  `positive_integer`), the `migration_key` composite type that packs a
  boundary's primary-key value, and the fixed-row chunk migration tables
  `migration_run` / `migration_boundary` (`migration_error` records failed
  chunks), plus their trigger functions.

Liquibase's own tracking tables are kept out of all three schemas: they live in
a dedicated `liquibase` schema as `liquibase.dml_utils_databasechangelog` and
`liquibase.dml_utils_databasechangeloglock`.

Every table carries `created_at`/`updated_at` (both `timestamptz NOT NULL
DEFAULT now()`), and the shared `dml_utils_data.set_updated_at()` trigger keeps
`updated_at` current on every `UPDATE`, so a caller cannot bypass it.

## Processing a table in chunks

`dml_utils.run_migration_chunks` runs a DML statement over a large table in
fixed-row chunks, one `pg_background` worker per chunk, so a long-running
backfill does not hold one giant statement (and one long transaction) on the
table. Each worker commits its own chunk, so progress is durable and a re-run
resumes where it stopped.

### How it works

You supply a SQL **template** with two placeholders that must each appear
exactly once:

- `<driving_table>` — replaced by `"<schema>"."<table>" "<alias>"`.
- `<chunking_clause>` — replaced by the chunk's primary-key range,
  `(<alias>.<pk> >= <start> AND <alias>.<pk> < <end>)` for every chunk except
  the last, which uses `<= <end>` so the captured maximum row is included.

The driving table must have a **single-column primary key** of type `smallint`,
`integer`, `bigint`, `text` or `uuid`; the column name is read from the catalog,
so it need not be `id`. The predicate compares the key to an explicitly cast
literal (`t.id >= '1'::bigint`) so the planner can use the primary-key index.
Chunks are cut by row number over `ORDER BY <pk>`; rows inserted later with keys
above the captured maximum are outside the final chunk and are not processed.

The first call for a `label` computes the boundaries (via
`populate_migration_boundaries`) and then processes them. Re-running with the
same `label` is safe: it resumes at the first unprocessed chunk using the SQL,
chunk size and driving table recorded when the run was created (a differing
`i_sql_text`, `i_chunk_size` or `i_driving_table_*` is ignored, with a notice),
and a run that is already complete is a no-op. To change the SQL of an existing run deliberately, use
`dml_utils.set_migration_run_sql_text`. Progress and completion are visible in
`dml_utils_data.migration_run` and `dml_utils_data.migration_boundary`.

### Example: backfill a column

Given a table with a single `bigint` primary key (any supported key type works
the same way):

```sql
CREATE TABLE app.events
(
    id      bigint PRIMARY KEY,
    payload jsonb,
    region  text
);
```

Backfill `region` in chunks of 10,000 rows:

```sql
SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET region = ''unknown'' WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'events',
               i_label => 'events-region-backfill',
               i_chunk_size => 10000);
```

`i_chunk_size` defaults to `1000` and must be positive; omit it to use the
default. `i_threads` defaults to `1` and processes that many chunks in parallel (each in its own worker); if one chunk
fails, the call stops launching new
chunks, lets the in-flight ones commit, and then raises the error, leaving the
run to be resumed. It must not exceed `max_worker_processes`, and parallel
chunks still contend for the same table's locks and pages, so it is not always
faster. The thread count is recorded on the run like the SQL text: a resumed
run uses the recorded value (a differing `i_threads` is ignored, with a notice),
and you can change it on an unfinished run with
`dml_utils.set_migration_run_threads(i_label, i_threads)`.

The worker SQL for the first chunk is:

```sql
UPDATE "app"."events" "t"
SET region = 'unknown'
WHERE (t.id >= '1'::bigint AND t.id < '10001'::bigint)
```

and the final chunk uses `t.id <= '<max>'::bigint`.

### Example: a custom alias

If the template refers to the driving table more than once, give it an alias so
every reference resolves:

```sql
SELECT dml_utils.run_migration_chunks(
               i_sql_text =>
                   'UPDATE <driving_table> SET payload = payload || ''{"migrated":true}'''
                       ' WHERE <chunking_clause>',
               i_driving_table_schema_name => 'app',
               i_driving_table_name => 'events',
               i_label => 'events-payload-migrate',
               i_chunk_size => 5000,
               i_driving_table_alias => 'e');
```

### Requirements and behavior

- `pg_background` must be installed and the caller must hold
  `pgbackground_role` (the custom image's init script grants it to
  `dml_utils_caller`).
- The routine is `SECURITY INVOKER`: the caller needs whatever privileges the
  chunk SQL needs on the driving table (typically `UPDATE`).
- Run under `READ COMMITTED`, and do not hold locks (or uncommitted writes) on
  the driving table across the call: a worker that needs a row the caller holds
  cannot make progress.
- The chunk SQL should be idempotent: a chunk whose worker failed is retried on
  the next run, and a chunk that committed is not run again.

### Inspecting and restarting a run

```sql
-- Is the run done, and when did it finish?
SELECT run_id,
       label,
       driving_table_schema_name,
       driving_table_name,
       chunk_size,
       completed_at,
       archived_at
FROM dml_utils_data.migration_run
WHERE label = 'events-region-backfill';

-- Per-chunk progress (completed_at IS NULL means still to do). boundary_id is a
-- migration_key; read the attribute for the table's key type, for example
-- (boundary_id).bigint_value for a bigint key.
SELECT boundary_no, boundary_id, (boundary_id).bigint_value, completed_at
FROM dml_utils_data.migration_boundary
WHERE run_id = (SELECT run_id
                FROM dml_utils_data.migration_run
                WHERE label = 'events-region-backfill'
                  AND archived_at IS NULL)
ORDER BY boundary_no;
```

A failed chunk is recorded in `migration_error`, so a run can be diagnosed
without the worker logs:

```sql
SELECT boundary_no, sqlstate, message, created_at
FROM dml_utils_data.migration_error
WHERE run_id = (SELECT run_id
                FROM dml_utils_data.migration_run
                WHERE label = 'events-region-backfill'
                  AND archived_at IS NULL)
ORDER BY created_at;
```

To re-run a label from scratch (for example after changing the chunk size),
archive the current run first; then the label is free again:

```sql
SELECT dml_utils.archive_migration_run(i_label => 'events-region-backfill');
```

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

- One test: `./mvnw -pl dml_utils -am test -Dtest=DomainTest`
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

`liquibase status` reports pending changesets; re-running `update` is safe (Liquibase skips changesets it has already
applied).

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

The build and local dev database use a custom image (`dml-utils-postgres:<ver>-alpine`) built from the official
`postgres:<ver>-alpine`
image. It bakes in a roles init script (`docker/postgres/roles.sql`) that
creates the application roles and the `liquibase` schema before Liquibase runs:

- `dml_utils_owner` — owns the schemas and objects; Liquibase connects as this
  role (never as `postgres`).
- `dml_utils_caller` — the role privileges are granted to.
- `dml_utils_test` — granted `dml_utils_caller`; used by the integration tests.

The image also compiles two extensions from source (pinned and
checksum-verified), so they are available in dev databases:

- [`plpgsql_check`](https://github.com/okbob/plpgsql_check) — static analysis
  of the routines.
- [`pg_background`](https://github.com/vibhorkum/pg_background) — runs SQL in
  background workers with autonomous transactions, used by the chunking
  routines.

Both are created by the init script and in every test database (via the
migrated template).

The init script only runs on first initialization, so an existing data volume
keeps its roles and installed extensions as-is; recreate the volume (`scripts/refresh-local-db.sh`) to pick up a new
image.

## Repository layout

```
dml_utils/src/main/resources/db/changelog/   Liquibase changelog (master + changes)
dml_utils/src/test/java/io/github/eyupmiduck/dmlutils/   JUnit tests
docker/postgres/                             Custom image (roles + extensions)
scripts/                                     Local DB and release helpers
compose.yaml                                 Local development database
```

## License

See [LICENSE](LICENSE).
