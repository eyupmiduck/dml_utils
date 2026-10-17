package io.github.eyupmiduck.dmlutils;

import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;

/**
 * dml_utils' PostgreSQL test base: the shared
 * {@link io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase}
 * configured for the dml_utils roles, schemas and changelog, plus the
 * dml_utils-specific fixture and run helpers.
 *
 * <p>The shared base starts one container, migrates a template database from
 * the production changelog and the dml_utils fixture changelog, and clones a
 * private database per test class as the {@code dml_utils_test} role.
 */
abstract class PostgresTestBase extends io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase {

    /**
     * The schema Liquibase keeps its tracking tables in, so they stay out of the
     * application schemas.
     */
    static final String LIQUIBASE_SCHEMA = "liquibase";
    static final String DATABASE_CHANGELOG_TABLE = "dml_utils_databasechangelog";
    static final String DATABASE_CHANGELOG_LOCK_TABLE = "dml_utils_databasechangeloglock";
    /**
     * The test-only fixture changelog (fixture tables the tests drive the
     * routines over), applied to the template database after the production
     * changelog.
     */
    static final String FIXTURES_RESOURCE = "db/changelog-fixtures/db.changelog-fixtures.xml";

    /**
     * The container configuration, shared by this base and
     * {@link LiquibaseUpgradeTest} so the two cannot drift (the container's
     * template database is prepared once per JVM from the first class that
     * runs, so an inconsistent configuration would be order-dependent).
     */
    static final String POSTGRES_IMAGE = "dml-utils-postgres:17-alpine";
    static final String DATABASE = "dml_utils";
    static final String OWNER_USER = "dml_utils_owner";
    static final String OWNER_PASSWORD = "dml_utils_owner";
    static final String TEST_USER = "dml_utils_test";
    static final String TEST_PASSWORD = "dml_utils_test";

    /**
     * Returns a jOOQ field reference by name, for fixture columns when no
     * generated field is available.
     *
     * @param name the column name
     * @param type the column's Java type
     * @param <T>  the column type
     * @return the field reference
     */
    protected static <T> Field<T> field(String name, Class<T> type) {
        return DSL.field(name, type);
    }

    /**
     * Installs the extensions the changelog's routines need into a database,
     * and grants the roles they require. Shared with
     * {@link LiquibaseUpgradeTest}, whose scratch database needs the same
     * extensions before the changelog runs.
     *
     * @param admin a superuser connection to the target database
     * @throws SQLException if the extensions cannot be installed
     */
    static void installProjectExtensions(Connection admin) throws SQLException {
        try (java.sql.Statement statement = admin.createStatement()) {
            // plpgsql_check for static analysis; pg_background for the chunking
            // routines, which declare its types, so the extension must exist
            // when Liquibase creates them.
            statement.execute("CREATE EXTENSION IF NOT EXISTS plpgsql_check");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_background");
            // pg_background grants no access to PUBLIC; grant its role to the
            // owner and test roles (membership is cluster-wide).
            statement.execute("GRANT pgbackground_role TO " + OWNER_USER);
            statement.execute("GRANT pgbackground_role TO " + TEST_USER);
        }
    }

    @Override
    protected String defaultPostgresImage() {
        return POSTGRES_IMAGE;
    }

    @Override
    protected String databaseName() {
        return DATABASE;
    }

    @Override
    protected String ownerUser() {
        return OWNER_USER;
    }

    @Override
    protected String ownerPassword() {
        return OWNER_PASSWORD;
    }

    @Override
    protected String testUser() {
        return TEST_USER;
    }

    @Override
    protected String testPassword() {
        return TEST_PASSWORD;
    }

    @Override
    protected String fixturesResource() {
        return FIXTURES_RESOURCE;
    }

    @Override
    protected String databaseChangeLogTableName() {
        return DATABASE_CHANGELOG_TABLE;
    }

    @Override
    protected String databaseChangeLogLockTableName() {
        return DATABASE_CHANGELOG_LOCK_TABLE;
    }

    /**
     * Evaluates a SQL expression and returns its single value as the given
     * Java type.
     *
     * <p>This is plain SQL on purpose: testing a domain's check constraint
     * means casting a literal to the domain, and jOOQ renders a cast to a
     * {@code Domain}'s data type as the base type, so the constraint would not
     * be exercised.
     *
     * @param expression the SQL expression to evaluate
     * @param type       the Java type to read the value as
     * @param <T>        the value type
     * @return the expression's value
     */
    protected <T> T evaluate(String expression, Class<T> type) {
        Record record = dsl.fetchOne("SELECT " + expression);
        return record.get(0, type);
    }

    /**
     * Reads the top-level plan node type from an {@code EXPLAIN (FORMAT JSON)}
     * document, so plan-content tests assert a statement's shape without
     * duplicating the json path.
     *
     * @param plan the {@code EXPLAIN (FORMAT JSON)} document
     * @return the plan's {@code Node Type}
     */
    protected String planNodeType(JSON plan) {
        return dsl.fetchOne("SELECT (?::json -> 0 -> 'Plan' ->> 'Node Type')",
                plan.data()).get(0, String.class);
    }

    /**
     * Inserts one row per id into {@code dml_utils_fixtures.test_bigint}, the
     * single-bigint-key fixture the routines are driven over.
     *
     * @param ids the primary-key values to insert
     */
    protected void seedBigint(long... ids) {
        for (long id : ids) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
    }

    /**
     * A per-invocation unique run label: {@code prefix-UUID}, so tests never
     * collide on the one-active-run-per-label rule regardless of order.
     *
     * @param prefix a short descriptive prefix
     * @return a unique label
     */
    protected String uniqueLabel(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * Whether the run with the given id has completed.
     *
     * @param runId the run id
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean runCompleted(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the run with the given label has completed.
     *
     * @param label the run label
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean runCompleted(String label) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the run with the given id has started processing.
     *
     * @param runId the run id
     * @return {@code true} when {@code started_at} is set
     */
    protected boolean runStarted(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.STARTED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.STARTED_AT.isNotNull()));
    }

    /**
     * Whether the boundary {@code (runId, boundaryNo)} has been claimed.
     *
     * @param runId      the run id
     * @param boundaryNo the boundary number
     * @return {@code true} when {@code completed_at} is set
     */
    protected boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    /**
     * Whether the boundary {@code (runId, boundaryNo)} has been claimed and
     * started processing.
     *
     * @param runId      the run id
     * @param boundaryNo the boundary number
     * @return {@code true} when {@code started_at} is set
     */
    protected boolean boundaryStarted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.STARTED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.STARTED_AT.isNotNull()));
    }

    @Override
    protected void installExtensions(String templateDatabase) throws SQLException {
        try (Connection admin = openAdminConnection(templateDatabase)) {
            installProjectExtensions(admin);
        }
    }
}
