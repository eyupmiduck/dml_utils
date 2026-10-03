package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.udt.records.MigrationKeyRecord;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(tableExists("dml_utils_data", "migration_run"), "migration_run should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "updated_at"), "updated_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "driving_table_schema_name"),
                "driving_table_schema_name should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_run", "driving_table_name"),
                "driving_table_name should exist");

        assertTrue(tableExists("dml_utils_data", "migration_boundary"), "migration_boundary should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "boundary_id"), "boundary_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_boundary", "updated_at"), "updated_at should exist");

        assertTrue(tableExists("dml_utils_data", "migration_error"), "migration_error should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "error_id"), "error_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "run_id"), "run_id should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "boundary_no"), "boundary_no should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "sqlstate"), "sqlstate should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "message"), "message should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils_data", "migration_error", "updated_at"), "updated_at should exist");
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_run} and
     * overwrites a caller-supplied value on UPDATE, so a caller cannot bypass
     * it.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnMigrationRun() {
        assertTrue(triggerExists("dml_utils_data", "migration_run", "migration_run_set_updated_at"),
                "migration_run_set_updated_at should be attached to dml_utils_data.migration_run");

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
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("migration-tables-test-" + UUID.randomUUID(), "SELECT 1", 1, 1,
                        PUBLIC_SCHEMA, "migration_tables_source")
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
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, null, null))
                .execute();
        return runId;
    }

    /**
     * The boundary key check constraint accepts a key with at least one
     * populated array.
     */
    @Test
    void acceptsASingleAttributeBoundaryKey() {
        Long runId = insertRun();

        dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(null, new String[]{"abc"}, null))
                .execute();

        assertEquals("abc", dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .getTextValues()[0]);
    }

    /**
     * A boundary key with no populated array violates the check constraint, and
     * so does an empty array (it carries no key value).
     */
    @Test
    void rejectsBoundaryKeysWithNoPopulatedOrEmptyArray() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(null, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{}, null, null))
                .execute());
    }

    /**
     * The boundary key check rejects present arrays of differing lengths, even
     * when no single index holds two values (the second case below passes the
     * per-index rule but not the shared-arity rule).
     */
    @Test
    void rejectsBoundaryKeysWithMismatchedArrayLengths() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L}, new String[]{"x"}, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L,
                        new MigrationKeyRecord(new Long[]{1L, null, 3L}, new String[]{null, "x"}, null))
                .execute());
    }

    /**
     * The boundary key check rejects an equal-length key with two values at the
     * same index: exactly one array may hold a value per position.
     */
    @Test
    void rejectsBoundaryKeysWithTwoValuesAtOneIndex() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{"abc"}, null))
                .execute());
    }

    /**
     * The boundary key check rejects a key of more than three columns (the
     * arity cap) and a present-but-all-NULL array, which only the non-emptiness
     * guard rejects.
     */
    @Test
    void rejectsBoundaryKeysOverTheArityCapOrWithAnAllNullArray() {
        Long runId = insertRun();

        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L, 2L, 3L, 4L}, null, null))
                .execute());
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_BOUNDARY)
                .columns(MIGRATION_BOUNDARY.RUN_ID, MIGRATION_BOUNDARY.BOUNDARY_NO,
                        MIGRATION_BOUNDARY.BOUNDARY_ID)
                .values(runId, 0L, new MigrationKeyRecord(new Long[]{1L}, new String[]{null}, null))
                .execute());
    }

    /**
     * A non-positive {@code chunk_size} is rejected by the check constraint.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("chunk-size-check", "SELECT 1", 0, 1, PUBLIC_SCHEMA, "migration_tables_source")
                .execute());
    }

    /**
     * {@code label}, {@code chunk_size} and the driving table schema/name are
     * immutable: updating any of them is rejected, while updating a mutable
     * column (for example {@code completed_at}) is allowed.
     */
    @Test
    void rejectsUpdatesToImmutableRunColumns() {
        Long runId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.LABEL, "changed")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.CHUNK_SIZE, 99)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, "other")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.DRIVING_TABLE_NAME, "other")
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute());

        // A mutable column can still be updated.
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * {@code run_id}, {@code boundary_no} and {@code boundary_id} are immutable:
     * updating any of them is rejected, while updating the mutable
     * {@code completed_at} is allowed.
     */
    @Test
    void rejectsUpdatesToImmutableBoundaryColumns() {
        long runId = insertRunWithBoundary();
        long otherRunId = insertRun();

        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_ID, new MigrationKeyRecord(new Long[]{999L}, null, null))
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.BOUNDARY_NO, 999L)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());
        assertSqlState("22023", () -> dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.RUN_ID, otherRunId)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute());

        // A mutable column can still be updated.
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();
    }

    /**
     * The shared timestamp columns are {@code timestamptz NOT NULL}, and the
     * primary and foreign keys are the composite ones the routines rely on.
     */
    @Test
    void migrationTableMetadataIsAsExpected() {
        for (String table : List.of("migration_run", "migration_boundary", "migration_error")) {
            for (String column : List.of("created_at", "updated_at")) {
                Record row = dsl.fetchOne(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = 'dml_utils_data'"
                                + " AND table_name = ? AND column_name = ?",
                        table, column);
                assertTrue(row != null, table + "." + column + " should exist");
                assertEquals("timestamp with time zone", row.get("data_type", String.class),
                        table + "." + column + " should be timestamptz");
                assertEquals("NO", row.get("is_nullable", String.class),
                        table + "." + column + " should be NOT NULL");
            }
        }

        assertEquals(List.of("run_id"), constraintColumns("migration_run", "p"),
                "migration_run's primary key");
        assertEquals(List.of("run_id", "boundary_no"),
                constraintColumns("migration_boundary", "p"),
                "migration_boundary's composite primary key");
        assertEquals(List.of("run_id", "boundary_no"),
                constraintColumns("migration_error", "f"),
                "migration_error's composite foreign key");
    }

    private List<String> constraintColumns(String table, String constraintType) {
        return dsl.fetch(
                "SELECT a.attname FROM pg_constraint con"
                        + " JOIN pg_class c ON c.oid = con.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)"
                        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum"
                        + " WHERE con.contype = ? AND n.nspname = 'dml_utils_data'"
                        + " AND c.relname = ? ORDER BY k.ord",
                constraintType, table).getValues(0, String.class);
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_boundary}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationBoundary() {
        assertTrue(triggerExists("dml_utils_data", "migration_boundary", "migration_boundary_set_updated_at"),
                "migration_boundary_set_updated_at should be attached to dml_utils_data.migration_boundary");
    }

    /**
     * The {@code updated_at} trigger is attached to {@code migration_error}.
     */
    @Test
    void updatedAtTriggerIsAttachedToMigrationError() {
        assertTrue(triggerExists("dml_utils_data", "migration_error", "migration_error_set_updated_at"),
                "migration_error_set_updated_at should be attached to dml_utils_data.migration_error");
    }

    /**
     * The {@code updated_at} trigger overwrites a caller-supplied timestamp on
     * UPDATE for both {@code migration_boundary} and {@code migration_error}, not
     * merely being attached to the table.
     */
    @Test
    void updatedAtIsMaintainedByTheTriggerOnBoundaryAndError() {
        long runId = insertRunWithBoundary();
        dsl.insertInto(MIGRATION_ERROR, MIGRATION_ERROR.RUN_ID, MIGRATION_ERROR.BOUNDARY_NO,
                        MIGRATION_ERROR.SQLSTATE, MIGRATION_ERROR.MESSAGE)
                .values(runId, 0L, "22012", "boom")
                .execute();

        OffsetDateTime old = OffsetDateTime.parse("2000-01-01T00:00:00Z");
        OffsetDateTime recent = OffsetDateTime.parse("2020-01-01T00:00:00Z");

        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.UPDATED_AT, old)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .execute();
        dsl.update(MIGRATION_ERROR)
                .set(MIGRATION_ERROR.UPDATED_AT, old)
                .set(MIGRATION_ERROR.MESSAGE, "changed")
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .execute();

        assertTrue(Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.UPDATED_AT.gt(recent))
                        .from(MIGRATION_BOUNDARY).where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                        .fetchOne(0, Boolean.class)),
                "the boundary trigger should overwrite updated_at");
        assertTrue(Boolean.TRUE.equals(dsl.select(MIGRATION_ERROR.UPDATED_AT.gt(recent))
                        .from(MIGRATION_ERROR).where(MIGRATION_ERROR.RUN_ID.eq(runId))
                        .fetchOne(0, Boolean.class)),
                "the error trigger should overwrite updated_at");
    }
}
