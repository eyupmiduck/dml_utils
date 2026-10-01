package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the Liquibase changelog loads the migration tables with the
 * expected columns and that the shared {@code set_updated_at()} trigger keeps
 * their {@code updated_at} current.
 */
class MigrationTablesTest extends PostgresTestBase {

    /**
     * Both migration tables exist in the {@code dml_utils} schema with the
     * audit columns.
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

        Long runId = dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE)
                .values("migration-tables-test", "SELECT 1", 1)
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);

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
     * The {@code updated_at} trigger is attached to {@code migration_boundary}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationBoundary() {
        assertTrue(triggerExists("dml_utils", "migration_boundary", "migration_boundary_set_updated_at"),
                "migration_boundary_set_updated_at should be attached to dml_utils.migration_boundary");
    }
}
