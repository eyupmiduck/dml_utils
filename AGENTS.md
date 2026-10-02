# AGENTS.md

## Project

This is a Java project for PostgreSQL DML tooling.

Primary technologies:

- Java 25
- Maven
- PostgreSQL
- jOOQ
- Liquibase
- JUnit 5
- Testcontainers

## Repo state

Maven multi-module project: `dml_utils` carries the Liquibase-managed schemas,
jOOQ codegen and tests; `docker_java_config` is a build shim. CI: GitHub Actions (`.github/workflows/maven.yml`) runs
`./mvnw clean verify` on pull requests to
`main`.

- Root `pom.xml`: parent POM (`dml-utils-parent`); all dependency and plugin
  versions are pinned here in `dependencyManagement` / `pluginManagement`.
- `dml_utils/`: the main module; base package
  `io.github.eyupmiduck.dmlutils`.
    - Liquibase changelogs: `src/main/resources/db/changelog/`
      (`db.changelog-master.xml` includes `changes/changes.xml`, which holds the
      schema/table changesets; forward SQL lives in `changes/sql_changes/`,
      rollback SQL in `changes/rollback/`). `changes/functions.xml` holds one
      changeset per routine (one `createProcedure` plus its rollback), with one
      file per routine under `changes/functions/<schema>/` and rollback bodies
      under `changes/functions-rollback/<schema>/`. `changes/functions/README.md`
      lists each routine's signature and purpose. `changes/triggers.xml` mirrors
      that layout for table triggers: one changeset per trigger (one `sqlFile`
      plus its rollback), with one file per trigger under
      `changes/triggers/<schema>/` and rollback bodies under
      `changes/triggers-rollback/<schema>/`. The schemas are layered:
      `dml_utils` is the caller-facing API, `dml_utils_lib` is the engine (generic helpers plus the internal routines
      that populate and process
      boundaries), and `dml_utils_data` is the data layer (the shared domains,
      the `migration_key` composite type and the migration tables). Dependencies
      point downward (`dml_utils` -> `dml_utils_lib` -> `dml_utils_data`);
      nothing lower references a schema above it.
    - jOOQ classes are generated at build time into
      `target/generated-sources/jooq` by
      `testcontainers-jooq-codegen-maven-plugin`, which starts a real
      PostgreSQL container and applies the Liquibase changelog. **Docker must be
      running for `./mvnw verify`.** Plugin 0.0.4 is old: the module POM
      overrides its bundled Testcontainers and jOOQ — keep those overrides.
- `docker_java_config/`: jar containing only `docker-java.properties`
  (`api.version=1.44`). Testcontainers <= 1.21.3 shades docker-java pinned to
  Docker API 1.32, but Docker 29 requires >= 1.40. This module puts the pin on
  the codegen plugin realm and the test classpath. Remove once Testcontainers
  supports Docker 29+ natively.

## Useful commands

- Everything: `./mvnw verify`
- One module: `./mvnw -pl dml_utils -am verify` (`-am` is required — reactor
  deps are not installed)
- One test: `./mvnw -pl dml_utils -am test -Dtest=DomainTest`
- Lint SQL only: `.venv/bin/sqlfluff lint dml_utils/src/main/resources/db/changelog`
- Auto-fix SQL style: `scripts/sqlfluff-fix.sh` (uses the repo's `.venv`)
- Lint the changelog semantics only (skip SQLFluff): `./mvnw -pl dml_utils -am verify -Dskip.sqlfluff`
- Skip the changelog linter only: `./mvnw verify -Dskip.liquibase-linter`
- Simulate a release deploy locally (file repo, no credentials):
  `./mvnw -Drevision=1.2.3 -DaltDeploymentRepository=local::file:/tmp/m2 deploy`

## Releases

- Versioning is CI-friendly: the root POM declares `<revision>` and uses
  `flatten-maven-plugin` (`resolveCiFriendliesOnly`), and each module's
  `<parent>` version is `${revision}`. `flatten` resolves it in the installed/
  deployed POM. A release does not edit the POM; a snapshot bump changes the
  single `<revision>` line.
- A release is a `v<version>` tag pushed to `main`. The `Release` workflow (`.github/workflows/release.yml`) derives the
  version from the tag (`-Drevision=${tag#v}`), runs the full `verify` gate, deploys to GitHub
  Packages, then creates the GitHub Release. Do not tag a commit that CI has
  not built green. Cut one with `scripts/cut-release.sh <version>`, which
  refuses anything but a clean, up-to-date `main` and a version newer than the
  greatest existing tag.
- The parent POM and `dml_utils` are published. `docker_java_config` is a build
  shim, so it sets `maven.deploy.skip` and is never deployed.

## Development principles

- Prefer simple, explicit Java over unnecessary abstractions.
- Use modern Java 25 features where they improve readability.
- Keep methods small and focused.
- Avoid adding dependencies unless there is a clear benefit.
- Do not introduce frameworks unless specifically requested.
- Follow the existing project structure and conventions.

## Maven

- Always use the Maven Wrapper:
  `./mvnw`
- Do not assume a globally installed Maven version.
- Changes should pass:
  `./mvnw verify`

## PostgreSQL

- Target PostgreSQL unless explicitly told otherwise.
- Prefer PostgreSQL-native solutions over database-portable abstractions.
- SQL must be safe for production-sized databases.
- Consider locking, transaction boundaries, concurrency, and failure recovery.
- Avoid operations that unnecessarily require long ACCESS EXCLUSIVE locks.
- Do not assume small tables.
- **Every table has `created_at` and `updated_at`.** Both are
  `timestamptz NOT NULL DEFAULT now()`; never `timestamp without time zone` and
  never a different column name. Attach the shared
  `dml_utils_data.set_updated_at()` trigger (`BEFORE UPDATE ... FOR EACH ROW`) to the
  table so `updated_at` is refreshed on every `UPDATE` regardless of the caller;
  a caller must not have to set it, and must not be able to bypass it. Create a
  table and add its `BEFORE UPDATE ... FOR EACH ROW` trigger as a new changeset
  in `changes/triggers.xml` with its body under `changes/triggers/<schema>/` (the
  shared function already exists). The migration tables are the reference
  implementation for the columns (`changes/sql_changes/005-create-migration-tables.sql`)
  and the triggers (`changes/triggers/dml_utils_data/`).
- **Every object has a comment.** Add a `COMMENT ON` for each schema, table,
  column, domain, function and procedure, describing what it is for. Comment a
  function or procedure at the end of the `.sql` file that creates it; comment
  every other object right after the statement that creates it, in the same
  changeset. Routine comments use the short form (`schema.name`); include the
  argument types only when the routine name is overloaded.

## PL/pgSQL

- **Prefer stored functions over stored procedures.** Functions cannot
  `COMMIT`/`ROLLBACK` or manage transactions, so transaction control can never
  leak into code that must run inside the caller's transaction. Use a procedure
  only when an operation genuinely needs several statements with a `COMMIT`
  between them.
- Prefix input arguments with `i_`, output arguments with `o_`, and local
  variables with `l_`. Use `snake_case` for object names, arguments, and
  variables.
- When one routine calls another, pass arguments **by name**
  (`dml_utils_lib.some_helper(i_x => ..., ...)`) rather than positionally, so
  reordering or inserting a parameter cannot silently rebind values.
- One routine per `.sql` file, grouped by the schema that owns it: functions in
  `dml_utils/src/main/resources/db/changelog/changes/functions/<schema>/<name>.sql`,
  rollback bodies in `.../changes/functions-rollback/<schema>/<name>-rollback.sql`,
  named `snake_case` without an `NNN-` prefix. `changes/functions.xml` contains
  one changeset per routine, with the id `function-<schema>.<name>` (one
  `createProcedure` plus its rollback). The schema is part of the id because the
  same routine name can exist in more than one schema.
  Overloads of one routine (same schema and name, different signature) share a
  single changeset.
- One trigger per `.sql` file, grouped by the schema of the table it is on:
  `.../changes/triggers/<schema>/<name>.sql`, rollback bodies in
  `.../changes/triggers-rollback/<schema>/<name>-rollback.sql`, named
  `snake_case` without an `NNN-` prefix. `changes/triggers.xml` contains one
  changeset per trigger, with the id `trigger-<schema>.<name>` (one `sqlFile`
  plus its rollback). Use `CREATE OR REPLACE TRIGGER` (PostgreSQL 14+) so the
  changeset is re-runnable without dropping the trigger first.
- Load a routine with the `createProcedure` change type and an external body:
  `<createProcedure path="functions/<schema>/<name>.sql" relativeToChangelogFile="true"/>`.
  Liquibase has no `createFunction` change type, so functions use
  `createProcedure` too; the `path` attribute keeps SQL out of the XML.
- `CREATE OR REPLACE` only replaces a routine with an identical signature.
  Changing `RETURNS` or a parameter name aborts the deploy, and changing a
  parameter type leaves the old overload behind. When a signature changes, add
  an explicit `DROP FUNCTION IF EXISTS <old signature>;` (for example another
  `sqlFile` in the same changeset) so the deprecated signature is removed.
- Type routine arguments with the `dml_utils_data` domains (for example
  `non_null_text`, `positive_integer`) so null or invalid inputs fail fast with
  a check-constraint violation.
- Use `SECURITY INVOKER` (the default). A routine must never require callers to
  hold privileges beyond what they would need to run its SQL directly: if a
  caller could run the statement itself, calling the routine must just work.
  Use `SECURITY DEFINER` only when a caller genuinely must perform an operation
  it lacks privileges for, and then pin a safe `search_path` and grant `EXECUTE`
  explicitly (revoking it from `PUBLIC`).
- Never build dynamic SQL by concatenating values. Quote identifiers with
  `format('... %I ...', ...)` and literals with `%L`, and reject input that
  cannot be safely parameterized (for example a fragment with multiple
  statements or a comment).
- Schema-qualify objects or set `search_path` explicitly so a routine behaves
  the same regardless of the caller's `search_path`.
- Routines run inside the caller's transaction: use `SET LOCAL` for
  transaction-scoped settings and never assume state survives a rollback.

## Liquibase

- Database schema changes must be implemented through Liquibase.
- Changesets should be small and focused.
- **Do not embed SQL in XML.** Put SQL in a `.sql` file and reference it with
  `<sqlFile path="..." relativeToChangelogFile="true"/>` (also for
  `<rollback>`). Add each changeset as a `<changeSet id="NNN-description">`
  entry in `dml_utils/src/main/resources/db/changelog/changes/changes.xml`,
  with forward SQL in `changes/sql_changes/NNN-description.sql` and rollback
  SQL in `changes/rollback/NNN-description-rollback.sql`. Stored routines are
  the exception: they use the `createProcedure` change type with a `path` to a
  per-routine `.sql` file (see the PL/pgSQL section). Triggers follow the same
  per-object layout: one `.sql` file per trigger under
  `changes/triggers/<schema>/`, referenced by `<sqlFile path="...">` from
  `changes/triggers.xml`, with the drop in `changes/triggers-rollback/<schema>/`.
  The validator (`liquibase-validation`) exempts `triggers/` and
  `triggers-rollback/` from the `NNN-` file-name rule, like `functions/`.
- Prefer changes that are safe to deploy against a live database.
- Consider rollback and idempotency where appropriate.
- Do not modify an already-deployed changeset unless explicitly instructed.
- Liquibase's tracking tables are kept out of the application schemas: they live
  in a dedicated `liquibase` schema as `dml_utils_databasechangelog` and
  `dml_utils_databasechangeloglock`. Every entry point sets this — the jOOQ
  codegen plugin (`dml_utils/pom.xml`), `PostgresTestBase`, and the CLI flags in
  `compose.yaml` — and the custom image's init script (`docker/postgres/roles.sql`)
  creates the schema, because Liquibase does not.
- SQLFluff (`.sqlfluff`, dialect `postgres`) lints the changelog `.sql` files
  during `verify` via `exec-maven-plugin`. Requires `sqlfluff` on PATH (use
  the repo's `.venv`); skip with `-Dskip.sqlfluff`.
- The Liquibase changelog linter (`liquibase-validation`) also runs during
  `verify` over the changelog directory. It applies rules that need both the SQL
  and the changeset attributes (for example a statement PostgreSQL forbids in a
  transaction must be in a `runInTransaction="false"` changeset and be that
  changeset's only statement), which SQLFluff and plpgsql_check cannot see.
  Configure it in `dml_utils/.liquibase-linter.yml`; accept known findings in
  `dml_utils/.liquibase-linter-whitelist.yml` (fail-closed: an unaccepted
  finding and a stale whitelist entry both fail the build); skip with
  `-Dskip.liquibase-linter`. A separate `Changelog linter` workflow uploads the
  same run as SARIF to GitHub code scanning; `verify` remains the gate.

## jOOQ

- Prefer jOOQ's type-safe DSL over constructing SQL strings manually.
- Use generated jOOQ classes where available.
- Do not duplicate database schema definitions in Java.
- Use plain SQL when PostgreSQL-specific functionality cannot be expressed
  clearly with the jOOQ DSL.
- The generated convenience facades can trip `-Werror` or fail at class
  initialisation:
    - `dml_utils/pom.xml` sets `globalUDTReferences=false`: the generated `UDTs`
      facade calls a static factory through an instance field, which `-Werror`
      rejects. The UDT type and record are still generated (for example
      `...jooq.dml_utils_data.udt.records.MigrationKeyRecord`).
    - Do not wrap a composite type that a table in the same schema uses in a
      domain. The generated `Domains` -> UDT -> schema class -> tables ->
      `Domains` initialisation cycle throws during class loading.
      `dml_utils_data.migration_key` is a bare composite type, and its "at least
      one populated array" rule is a table check constraint (`migration_boundary_key_check`). It packs a boundary's
      primary-key value
      as position-aligned arrays, one per key kind, so a primary key of one to
      three columns is supported.

## Testing

- Use JUnit 5.
- Integration tests must use Testcontainers where a real PostgreSQL database
  is required.
- Do not replace PostgreSQL integration tests with H2 or another database.
  This also applies to tooling: no H2 anywhere, including jOOQ code
  generation (do not use jOOQ's offline `LiquibaseDatabase`/H2 simulation).
- Tests should be deterministic and independent.
- Database tests must extend `PostgresTestBase` (in `dml_utils` test
  sources). It shares one PostgreSQL container, applies the Liquibase
  changelog once to a template database, and gives each test class a private
  database cloned with `CREATE DATABASE ... TEMPLATE ...` (fast, isolated
  data). Use the inherited `dsl` (jOOQ); do not run Liquibase or start
  containers in individual tests.
- Every test class and test method must have Javadoc describing the behavior
  it verifies.
- Prefer testing observable behavior rather than implementation details.
- Add regression tests when fixing bugs.
- Drive the routines over the shared **test fixtures**, not tables created ad
  hoc in each test. The fixtures are Liquibase-managed in
  `src/test/resources/db/changelog-fixtures/` (their own changelog, never part
  of the production master), applied to the template database and granted to
  `dml_utils_test`. A second, test-scoped codegen execution (`generate-jooq-fixtures`)
  generates their jOOQ classes into `target/generated-test-sources/jooq`
  (package `...jooqfixtures`), which compile into the test classpath only. Add a
  fixture table there (one per shape the tests need) rather than calling
  `CREATE TABLE` in a test, and reset it with `truncate` between tests.
- The routines are statically analysed with the `plpgsql_check` extension (`PlpgsqlCheckTest`). It is compiled into the
  custom image, created in the
  template database, and available in dev databases via
  `docker/postgres/roles.sql`; keep the routines free of its warnings.

## Before completing a change

1. Review the diff.
2. Remove unnecessary code and imports.
3. Check for accidental API or schema changes.
4. Run relevant tests.
5. Run `./mvnw verify`.
6. Report any tests that could not be run.

## Git

- Never commit directly to `main`.
- Work on a feature branch.
- Keep commits focused.
- Do not commit generated build output, secrets, credentials, or local IDE files.
- Never merge a pull request without explicit approval. Open the PR, wait for
  CI and review, then ask; do not merge it yourself.
