package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.process_migration_chunk}: it claims a boundary,
 * runs the supplied chunk SQL, and records completion, and raises
 * {@code P0002} for a missing or already-completed boundary.
 */
class ProcessMigrationChunkTest extends PostgresTestBase {

    private static final String SOURCE = "process_chunk_source";
    private static final String SOURCE_QUALIFIED = PUBLIC_SCHEMA + "." + SOURCE;

    @AfterEach
    void dropSource() {
        dropTestTable(SOURCE_QUALIFIED);
    }

    /**
     * Running a chunk marks the rows in range and records the boundary as
     * completed.
     */
    @Test
    void runsChunkSqlAndClaimsTheBoundary() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        long runId = populate(4);

        processChunk(runId, 0, "UPDATE " + SOURCE_QUALIFIED
                + " SET payload = 'done' WHERE id >= 1 AND id < 5");

        assertTrue(boundaryCompleted(runId, 0), "the first chunk should set completed_at");
        assertEquals(4, payloadCount(), "rows 1..4 should be updated");
    }

    /**
     * An unknown boundary raises {@code P0002}.
     */
    @Test
    void rejectsMissingBoundary() {
        createSource(1, 2, 3);
        long runId = populate(2);

        assertSqlState("P0002", () -> processChunk(runId, 999, "SELECT 1"));
    }

    /**
     * An already-completed boundary raises {@code P0002} and does not run the
     * SQL again.
     */
    @Test
    void rejectsAlreadyCompletedBoundary() {
        createSource(1, 2, 3, 4);
        long runId = populate(2);

        processChunk(runId, 0, "UPDATE " + SOURCE_QUALIFIED
                + " SET payload = 'done' WHERE id >= 1 AND id < 3");

        assertSqlState("P0002", () -> processChunk(runId, 0, "UPDATE " + SOURCE_QUALIFIED
                + " SET payload = 'again' WHERE id >= 1 AND id < 3"));
        assertEquals(2, payloadCount(), "the second call must not run the SQL");
    }

    private void createSource(long... ids) {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        for (long id : ids) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
    }

    private long populate(int chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE,
                "process-chunk-test-" + System.nanoTime(), "SELECT 1", chunkSize);
    }

    private void processChunk(long runId, long boundaryNo, String sql) {
        Routines.processMigrationChunk(dsl.configuration(), runId, boundaryNo, sql);
    }

    private boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT completed_at IS NOT NULL FROM dml_utils.migration_boundary"
                        + " WHERE run_id = ? AND boundary_no = ?", runId, boundaryNo));
    }

    private int payloadCount() {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM " + SOURCE_QUALIFIED + " WHERE payload = 'done'")
                .get(0, Integer.class);
    }
}
