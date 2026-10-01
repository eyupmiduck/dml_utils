package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositePk.TEST_COMPOSITE_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestInteger.TEST_INTEGER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNoPk.TEST_NO_PK;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNumeric.TEST_NUMERIC;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestSmallint.TEST_SMALLINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code dml_utils_lib.populate_migration_boundaries} rejects invalid
 * input through its argument domains and its catalog validations, and writes
 * nothing when validation fails.
 */
class MigrationBoundaryValidationTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String NO_SUCH_TABLE = "no_such_table";
    private static final String SQL_TEXT = "SELECT 1";

    /**
     * Each test gets a distinct label so the one-active-run-per-label rule does
     * not couple tests that populate in the same database.
     */
    private int labelCounter;

    @BeforeEach
    void resetFixtures() {
        for (Table<?> table : java.util.List.of(TEST_BIGINT, TEST_INTEGER, TEST_SMALLINT, TEST_TEXT,
                TEST_UUID, TEST_NO_PK, TEST_COMPOSITE_PK, TEST_NUMERIC)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * A null schema name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSchemaName() {
        assertDomainViolation(() -> populateByNames(null, TEST_BIGINT.getName(), 1));
    }

    /**
     * A null table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullTableName() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, null, 1));
    }

    /**
     * A blank table name is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankTableName() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, "   ", 1));
    }

    /**
     * A null label is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), null, SQL_TEXT, 1));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "   ", SQL_TEXT, 1));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "null-sql-text", null, 1));
    }

    /**
     * A blank SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankSqlText() {
        assertDomainViolation(() -> Routines.populateMigrationBoundaries(
                dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName(), "blank-sql-text", "   ", 1));
    }

    /**
     * A zero chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsZeroChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), 0));
    }

    /**
     * A negative chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNegativeChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), -1));
    }

    /**
     * A null chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNullChunkSize() {
        assertDomainViolation(() -> populateByNames(FIXTURE_SCHEMA, TEST_BIGINT.getName(), null));
    }

    /**
     * An unknown schema fails with invalid_schema_name (3F000).
     */
    @Test
    void rejectsUnknownSchema() {
        assertSqlState("3F000", () -> populateByNames("no_such_schema", TEST_BIGINT.getName(), 1));
    }

    /**
     * An unknown table fails with undefined_table (42P01).
     */
    @Test
    void rejectsUnknownTable() {
        assertSqlState("42P01", () -> populateByNames(FIXTURE_SCHEMA, NO_SUCH_TABLE, 1));
    }

    /**
     * A table without a primary key fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsTableWithoutPrimaryKey() {
        assertSqlState("22023", () -> populateTable(TEST_NO_PK, 1));
    }

    /**
     * A composite primary key fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsCompositePrimaryKey() {
        assertSqlState("22023", () -> populateTable(TEST_COMPOSITE_PK, 1));
    }

    /**
     * A single primary-key column whose type is not supported for chunking
     * fails with invalid_parameter_value (22023).
     */
    @Test
    void rejectsUnsupportedPrimaryKeyType() {
        assertSqlState("22023", () -> populateTable(TEST_NUMERIC, 1));
    }

    /**
     * A single integer primary key is supported: the boundaries are computed.
     */
    @Test
    void acceptsAnIntegerPrimaryKey() {
        dsl.insertInto(TEST_INTEGER, TEST_INTEGER.ID).values(1).values(2).values(3).execute();

        long runId = populateTable(TEST_INTEGER, 2);

        assertTrue(runId > 0, "an integer primary key should be accepted");
    }

    /**
     * A single smallint primary key is supported.
     */
    @Test
    void acceptsASmallintPrimaryKey() {
        dsl.insertInto(TEST_SMALLINT, TEST_SMALLINT.ID).values((short) 1).values((short) 2)
                .values((short) 3).execute();

        long runId = populateTable(TEST_SMALLINT, 2);

        assertTrue(runId > 0, "a smallint primary key should be accepted");
    }

    /**
     * The primary-key helper returns the single column name for a valid table.
     */
    @Test
    void singleColumnPrimaryKeyReturnsTheColumnName() {
        String column = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .singleColumnPrimaryKey(dsl.configuration(), FIXTURE_SCHEMA, TEST_BIGINT.getName());

        assertEquals("id", column);
    }

    /**
     * The key-kind helper reports {@code bigint} for every supported integer
     * primary-key type.
     */
    @Test
    void primaryKeyKindIsBigintForIntegerTypes() {
        assertEquals("bigint", primaryKeyKind(TEST_SMALLINT));
        assertEquals("bigint", primaryKeyKind(TEST_INTEGER));
        assertEquals("bigint", primaryKeyKind(TEST_BIGINT));
    }

    /**
     * The key-kind helper reports {@code text} for a text primary key.
     */
    @Test
    void primaryKeyKindIsTextForText() {
        assertEquals("text", primaryKeyKind(TEST_TEXT));
    }

    /**
     * The key-kind helper reports {@code uuid} for a uuid primary key.
     */
    @Test
    void primaryKeyKindIsUuidForUuid() {
        assertEquals("uuid", primaryKeyKind(TEST_UUID));
    }

    /**
     * The key-kind helper rejects a primary-key type that cannot be chunked
     * with {@code 22023}.
     */
    @Test
    void primaryKeyKindRejectsUnsupportedType() {
        assertSqlState("22023", () -> primaryKeyKind(TEST_NUMERIC));
    }

    /**
     * A failed validation leaves the migration tables untouched.
     */
    @Test
    void writesNothingWhenValidationFails() {
        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        int boundariesBefore = dsl.fetchCount(MIGRATION_BOUNDARY);

        assertSqlState("42P01", () -> populateByNames(FIXTURE_SCHEMA, NO_SUCH_TABLE, 1));

        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN), "no run should be written");
        assertEquals(boundariesBefore, dsl.fetchCount(MIGRATION_BOUNDARY),
                "no boundary should be written");
    }

    /**
     * A second run with the same label while the first is active fails with
     * unique_violation (23505) and writes nothing.
     */
    @Test
    void rejectsLabelWithAnActiveRun() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(1L).execute();

        String label = uniqueLabel();
        long firstRun = populateTable(TEST_BIGINT, label, 1);
        int runsAfterFirst = dsl.fetchCount(MIGRATION_RUN);

        assertSqlState("23505", () -> populateTable(TEST_BIGINT, label, 1));

        assertEquals(runsAfterFirst, dsl.fetchCount(MIGRATION_RUN),
                "the rejected run should not be written");
        assertEquals(2, boundaries(firstRun).intValue(), "the first run is untouched");
    }

    /**
     * Once the existing run is archived, the label can be reused.
     */
    @Test
    void allowsLabelReuseAfterTheRunIsArchived() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(1L).execute();

        String label = uniqueLabel();
        long firstRun = populateTable(TEST_BIGINT, label, 1);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.ARCHIVED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(firstRun))
                .execute();

        long secondRun = populateTable(TEST_BIGINT, label, 1);

        assertNotEquals(firstRun, secondRun, "a new run should be created");
        assertEquals(2, runCount(label), "both runs for the label should exist");
    }

    /**
     * Calls {@code dml_utils_lib.primary_key_kind} for the fixture table.
     */
    private String primaryKeyKind(Table<?> table) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .primaryKeyKind(dsl.configuration(), table.getSchema().getName(), table.getName());
    }

    private long populateTable(Table<?> table, int chunkSize) {
        return populateTable(table, uniqueLabel(), chunkSize);
    }

    private long populateTable(Table<?> table, String label, int chunkSize) {
        return populateByNames(table.getSchema().getName(), table.getName(), label, chunkSize);
    }

    private long populateByNames(String schema, String table, Integer chunkSize) {
        return populateByNames(schema, table, uniqueLabel(), chunkSize);
    }

    private long populateByNames(String schema, String table, String label, Integer chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), schema, table, label, SQL_TEXT, chunkSize);
    }

    private String uniqueLabel() {
        return "validation-run-" + ++labelCounter;
    }

    private int runCount(String label) {
        return dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label));
    }

    private Integer boundaries(long runId) {
        return dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId));
    }
}
