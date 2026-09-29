package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@code dml_utils.populate_migration_boundaries} rejects invalid
 * input through its argument domains and its catalog validations, and writes
 * nothing when validation fails.
 */
class MigrationBoundaryValidationTest extends PostgresTestBase {

    private static final String TABLE = "migration_validation_source";
    private static final String TABLE_QUALIFIED = PUBLIC_SCHEMA + "." + TABLE;

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

    private long populate(String schema, String table, Integer chunkSize) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), schema, table, chunkSize);
    }

    private int countRows(String qualifiedTable) {
        return dsl.fetchOne("SELECT count(*)::int FROM " + qualifiedTable).get(0, Integer.class);
    }
}
