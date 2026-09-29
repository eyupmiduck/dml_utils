package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

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

        dsl.execute("INSERT INTO dml_utils.migration_run DEFAULT VALUES RETURNING run_id");
        Long runId = dsl.fetchOne("SELECT max(run_id) FROM dml_utils.migration_run")
                .get(0, Long.class);

        dsl.execute("UPDATE dml_utils.migration_run"
                + " SET updated_at = timestamptz '2000-01-01 00:00:00+00'"
                + " WHERE run_id = ?", runId);

        Object recent = dsl.fetchValue(
                "SELECT updated_at > timestamptz '2020-01-01 00:00:00+00'"
                        + " FROM dml_utils.migration_run WHERE run_id = ?", runId);
        assertTrue(Boolean.TRUE.equals(recent),
                "the trigger should overwrite updated_at with the transaction timestamp");
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
