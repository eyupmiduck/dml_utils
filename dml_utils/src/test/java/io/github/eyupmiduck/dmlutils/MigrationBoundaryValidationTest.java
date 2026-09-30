package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that {@code dml_utils.populate_migration_boundaries} rejects invalid
 * input through its argument domains and its catalog validations, and writes
 * nothing when validation fails.
 */
class MigrationBoundaryValidationTest extends PostgresTestBase {

    private static final String TABLE = "migration_validation_source";
    private static final String TABLE_QUALIFIED = PUBLIC_SCHEMA + "." + TABLE;
    private static final String SQL_TEXT = "SELECT 1";

    /**
     * Each test gets a distinct label so the one-active-run-per-label rule does
     * not couple tests that populate in the same database.
     */
    private int labelCounter;

    @AfterEach
    void dropSource() {
        dropTestTable(TABLE_QUALIFIED);
    }

    /**
     * A null schema name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSchemaName() {
        assertDomainViolation(() -> populate(null, TABLE, 1));
    }

    /**
     * A null table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullTableName() {
        assertDomainViolation(() -> populate(PUBLIC_SCHEMA, null, 1));
    }

    /**
     * A blank table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankTableName() {
        assertDomainViolation(() -> populate(PUBLIC_SCHEMA, "   ", 1));
    }

    /**
     * A null label is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, TABLE, null, SQL_TEXT, 1));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, TABLE, "   ", SQL_TEXT, 1));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, TABLE, "null-sql-text", null, 1));
    }

    /**
     * A blank SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, TABLE, "blank-sql-text", "   ", 1));
    }

    /**
     * A zero chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsZeroChunkSize() {
        assertDomainViolation(() -> populate(PUBLIC_SCHEMA, TABLE, 0));
    }

    /**
     * A negative chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNegativeChunkSize() {
        assertDomainViolation(() -> populate(PUBLIC_SCHEMA, TABLE, -1));
    }

    /**
     * A null chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNullChunkSize() {
        assertDomainViolation(() -> populate(PUBLIC_SCHEMA, TABLE, null));
    }

    /**
     * An unknown schema fails with invalid_schema_name (3F000).
     */
    @Test
    void rejectsUnknownSchema() {
        assertSqlState("3F000", () -> populate("no_such_schema", TABLE, 1));
    }

    /**
     * An unknown table fails with undefined_table (42P01).
     */
    @Test
    void rejectsUnknownTable() {
        assertSqlState("42P01", () -> populate(PUBLIC_SCHEMA, TABLE, 1));
    }

    /**
     * A table without a primary key fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsTableWithoutPrimaryKey() {
        createTestTable(TABLE_QUALIFIED, "id bigint, payload text");

        assertSqlState("22023", () -> populate(PUBLIC_SCHEMA, TABLE, 1));
    }

    /**
     * A composite primary key fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsCompositePrimaryKey() {
        createTestTable(TABLE_QUALIFIED, "a bigint, b bigint, PRIMARY KEY (a, b)");

        assertSqlState("22023", () -> populate(PUBLIC_SCHEMA, TABLE, 1));
    }

    /**
     * A single primary-key column that is not bigint fails with
     * invalid_parameter_value (22023).
     */
    @Test
    void rejectsNonBigintPrimaryKey() {
        createTestTable(TABLE_QUALIFIED, "id integer PRIMARY KEY");

        assertSqlState("22023", () -> populate(PUBLIC_SCHEMA, TABLE, 1));
    }

    /**
     * The primary-key helper returns the single column name for a valid table.
     */
    @Test
    void singleColumnPrimaryKeyReturnsTheColumnName() {
        createTestTable(TABLE_QUALIFIED, "id bigint PRIMARY KEY");

        String column = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .singleColumnPrimaryKey(dsl.configuration(), PUBLIC_SCHEMA, TABLE);

        assertEquals("id", column);
    }

    /**
     * A failed validation leaves the migration tables untouched.
     */
    @Test
    void writesNothingWhenValidationFails() {
        int runsBefore = countRows("dml_utils.migration_run");
        int boundariesBefore = countRows("dml_utils.migration_boundary");

        assertSqlState("42P01", () -> populate(PUBLIC_SCHEMA, TABLE, 1));

        assertEquals(runsBefore, countRows("dml_utils.migration_run"), "no run should be written");
        assertEquals(boundariesBefore, countRows("dml_utils.migration_boundary"),
                "no boundary should be written");
    }

    /**
     * A second run with the same label while the first is active fails with
     * unique_violation (23505) and writes nothing.
     */
    @Test
    void rejectsLabelWithAnActiveRun() {
        createTestTable(TABLE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        dsl.execute("INSERT INTO " + TABLE_QUALIFIED + " (id) VALUES (1)");

        String label = uniqueLabel();
        long firstRun = populate(label);
        int runsAfterFirst = countRows("dml_utils.migration_run");

        assertSqlState("23505", () -> populate(label));

        assertEquals(runsAfterFirst, countRows("dml_utils.migration_run"),
                "the rejected run should not be written");
        assertEquals(2, boundaries(firstRun).intValue(), "the first run is untouched");
    }

    /**
     * Once the existing run is archived, the label can be reused.
     */
    @Test
    void allowsLabelReuseAfterTheRunIsArchived() {
        createTestTable(TABLE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        dsl.execute("INSERT INTO " + TABLE_QUALIFIED + " (id) VALUES (1)");

        String label = uniqueLabel();
        long firstRun = populate(label);
        dsl.execute("UPDATE dml_utils.migration_run"
                + " SET archived_at = pg_catalog.now() WHERE run_id = ?", firstRun);

        long secondRun = populate(label);

        assertNotEquals(firstRun, secondRun, "a new run should be created");
        assertEquals(2, runCount(label), "both runs for the label should exist");
    }

    private long populate(String schema, String table, Integer chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), schema, table, uniqueLabel(), SQL_TEXT, chunkSize);
    }

    private long populate(String label) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, TABLE, label, SQL_TEXT, 1);
    }

    private String uniqueLabel() {
        return "validation-run-" + ++labelCounter;
    }

    private int runCount(String label) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM dml_utils.migration_run WHERE label = ?", label)
                .get(0, Integer.class);
    }

    private Integer boundaries(long runId) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM dml_utils.migration_boundary WHERE run_id = ?",
                        runId)
                .get(0, Integer.class);
    }

    private int countRows(String qualifiedTable) {
        return dsl.fetchOne("SELECT count(*)::int FROM " + qualifiedTable).get(0, Integer.class);
    }
}
