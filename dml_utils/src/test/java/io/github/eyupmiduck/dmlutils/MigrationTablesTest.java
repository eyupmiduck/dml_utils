package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.udt.records.MigrationKeyRecord;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the Liquibase changelog loads the migration tables with the
 * expected columns and that the shared {@code set_updated_at()} trigger keeps
 * their {@code updated_at} current.
 */
class MigrationTablesTest extends PostgresTestBase {

    /**
     * The migration tables exist in the {@code dml_utils} schema with the
     * expected columns.
     */
    @Test
    void migrationTablesAreLoaded() {
        assertTrue(tableExists("dml_utils", "migration_run"), "migration_run should exist");
        assertTrue(hasColumn("dml_utils", "migration_run", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils", "migration_run", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils", "migration_run", "updated_at"), "updated_at should exist");

        assertTrue(tableExists("dml_utils", "migration_boundary"), "migration_boundary should exist");
        assertTrue(hasColumn("dml_utils", "migration_boundary", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils", "migration_boundary", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils", "migration_boundary", "boundary_id"), "boundary_id should exist");
        assertTrue(hasColumn("dml_utils", "migration_boundary", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils", "migration_boundary", "updated_at"), "updated_at should exist");

        assertTrue(tableExists("dml_utils", "migration_error"), "migration_error should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "error_id"), "error_id should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "sqlstate"), "sqlstate should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "message"), "message should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils", "migration_error", "updated_at"), "updated_at should exist");
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_run} and
     * overwrites a caller-supplied value on UPDATE, so a caller cannot bypass
     * it.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnMigrationRun() {
        assertTrue(triggerExists("dml_utils", "migration_run", "migration_run_set_updated_at"),
                "migration_run_set_updated_at should be attached to dml_utils.migration_run");

        Long runId = insertRun();

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.UPDATED_AT, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();

        Boolean recent = dsl.select(MIGRATION_RUN.UPDATED_AT.gt(OffsetDateTime.parse("2020-01-01T00:00:00Z")))
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(0, Boolean.class);
        assertTrue(Boolean.TRUE.equals(recent),
                "the trigger should overwrite updated_at with the transaction timestamp");
    }

    /**
     * Inserts a run with a unique label, returning its {@code run_id}.
     */
    private Long insertRun() {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE)
                .values("migration-tables-test-" + System.nanoTime(), "SELECT 1", 1)
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    /**
     * Inserts a run and one boundary row, returning the {@code run_id}.
     */
    private long insertRunWithBoundary() {
        Long runId = insertRun();
        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(1L, null, null))
                .execute();
        return runId;
    }

    /**
     * A non-positive {@code chunk_size} is rejected by the check constraint.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE)
                .values("chunk-size-check", "SELECT 1", 0)
                .execute());
    }

    /**
     * {@code label} and {@code chunk_size} are immutable: updating either is
     * rejected, while updating a mutable column (for example {@code completed_at})
     * is allowed.
     */
    @Test
    void rejectsUpdatesToLabelAndChunkSize() {
        Long runId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.LABEL, "changed")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CHUNK_SIZE, 99)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        // A mutable column can still be updated.
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * {@code boundary_no} and {@code boundary_id} are immutable: updating either
     * is rejected, while updating the mutable {@code completed_at} is allowed.
     */
    @Test
    void rejectsUpdatesToBoundaryNoAndBoundaryId() {
        long runId = insertRunWithBoundary();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_ID, new MigrationKeyRecord(999L, null, null))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_NO, 999L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());

        // A mutable column can still be updated.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_boundary}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationBoundary() {
        assertTrue(triggerExists("dml_utils", "migration_boundary", "migration_boundary_set_updated_at"),
                "migration_boundary_set_updated_at should be attached to dml_utils.migration_boundary");
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_error}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationError() {
        assertTrue(triggerExists("dml_utils", "migration_error", "migration_error_set_updated_at"),
                "migration_error_set_updated_at should be attached to dml_utils.migration_error");
    }
}
