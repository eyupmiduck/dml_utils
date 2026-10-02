package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationErrorRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils_lib.record_migration_error}: it inserts an error row for
 * a migration boundary, rejects invalid input, requires an existing boundary,
 * and is removed when the boundary is deleted.
 */
class MigrationErrorTest extends PostgresTestBase {

    /**
     * The error row is recorded with the given run, boundary, SQLSTATE and
     * message.
     */
    @Test
    void recordsTheErrorRow() {
        Long runId = insertRunWithBoundary();

        Routines.recordMigrationError(
                dsl.configuration(), runId, 0L, "22012", "division by zero");

        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error, "an error row should be recorded");
        assertEquals(runId, error.getRunId());
        assertEquals(0L, error.getBoundaryNo());
        assertEquals("22012", error.getSqlstate());
        assertEquals("division by zero", error.getMessage());
        assertNotNull(error.getCreatedAt());
        assertNotNull(error.getUpdatedAt());
    }

    /**
     * A null or blank SQLSTATE or message is rejected by the non-null text
     * domain.
     */
    @Test
    void rejectsNullAndBlankInputs() {
        Long runId = insertRunWithBoundary();

        assertDomainViolation(() -> Routines.recordMigrationError(
                dsl.configuration(), runId, 0L, null, "boom"));
        assertDomainViolation(() -> Routines.recordMigrationError(
                dsl.configuration(), runId, 0L, "   ", "boom"));
        assertDomainViolation(() -> Routines.recordMigrationError(
                dsl.configuration(), runId, 0L, "22012", null));
        assertDomainViolation(() -> Routines.recordMigrationError(
                dsl.configuration(), runId, 0L, "22012", "   "));
    }

    /**
     * Recording an error for a boundary that does not exist fails with a
     * foreign-key violation.
     */
    @Test
    void rejectsAnUnknownBoundary() {
        Long runId = insertRunWithBoundary();

        assertSqlState("23503", () -> Routines.recordMigrationError(
                dsl.configuration(), runId, 999L, "22012", "boom"));
    }

    /**
     * Deleting the boundary cascades to its error rows.
     */
    @Test
    void cascadesWhenTheBoundaryIsDeleted() {
        Long runId = insertRunWithBoundary();
        Routines.recordMigrationError(dsl.configuration(), runId, 0L, "22012", "boom");

        dsl.deleteFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L))
                .execute();

        assertEquals(0, dsl.fetchCount(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID.eq(runId)),
                "the error row should be cascaded");
    }

    private Long insertRunWithBoundary() {
        Long runId = dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("migration-error-test-" + System.nanoTime(), "SELECT 1", 1, 1,
                        PUBLIC_SCHEMA, "migration_error_source")
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, null, null))
                .execute();
        return runId;
    }
}
