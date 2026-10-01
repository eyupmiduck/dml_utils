package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.process_migration_chunk}: it claims a boundary,
 * runs the supplied chunk SQL, and records completion, and raises
 * {@code P0002} for a missing or already-completed boundary.
 */
class ProcessMigrationChunkTest extends PostgresTestBase {

    private static String qualified(org.jooq.Table<?> table) {
        return table.getSchema().getName() + "." + table.getName();
    }

    @BeforeEach
    void resetFixtures() {
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Running a chunk marks the rows in range and records the boundary as
     * completed.
     */
    @Test
    void runsChunkSqlAndClaimsTheBoundary() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        long runId = populate(4);

        processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
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

        processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'done' WHERE id >= 1 AND id < 3");

        assertSqlState("P0002", () -> processChunk(runId, 0, "UPDATE " + qualified(TEST_BIGINT)
                + " SET payload = 'again' WHERE id >= 1 AND id < 3"));
        assertEquals(2, payloadCount(), "the second call must not run the SQL");
    }

    private void createSource(long... ids) {
        for (long id : ids) {
            dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID).values(id).execute();
        }
    }

    private long populate(int chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                "process-chunk-test-" + System.nanoTime(), "SELECT 1", chunkSize);
    }

    private void processChunk(long runId, long boundaryNo, String sql) {
        Routines.processMigrationChunk(dsl.configuration(), runId, boundaryNo, sql);
    }

    private boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    private int payloadCount() {
        return dsl.selectCount()
                .from(TEST_BIGINT)
                .where(TEST_BIGINT.PAYLOAD.eq("done"))
                .fetchOne(0, Integer.class);
    }
}
