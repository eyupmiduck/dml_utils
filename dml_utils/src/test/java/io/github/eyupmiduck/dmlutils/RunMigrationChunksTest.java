package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.run_migration_chunks}: it processes every fixed-row
 * chunk of the driving table via pg_background workers, records run and
 * boundary completion, resumes from the first unclaimed boundary, and is a
 * no-op for an already-completed run.
 */
class RunMigrationChunksTest extends PostgresTestBase {

    private static final String SOURCE = "run_chunks_source";
    private static final String SOURCE_QUALIFIED = PUBLIC_SCHEMA + "." + SOURCE;
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    @AfterEach
    void dropSource() {
        dropTestTable(SOURCE_QUALIFIED);
    }

    /**
     * Every row is processed, every chunk boundary is claimed, and the run is
     * marked complete.
     */
    @Test
    void processesAllChunksAndCompletesTheRun() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = label("all");

        run(label, 4);

        assertEquals(10, doneCount(), "all rows should be updated");
        long runId = runId(label);

        // chunk starts at 1, 5, 9 and the terminal high-water boundary at 10.
        assertBoundaries(runId, new long[][]{{0, 1}, {1, 5}, {2, 9}, {3, 10}});
        assertTrue(boundaryCompleted(runId, 0), "chunk 0 should be completed");
        assertTrue(boundaryCompleted(runId, 1), "chunk 1 should be completed");
        assertTrue(boundaryCompleted(runId, 2), "chunk 2 should be completed");
        assertTrue(!boundaryCompleted(runId, 3),
                "the terminal boundary is not a chunk and stays unclaimed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * A row count that is not a multiple of the chunk size still processes every
     * row: the last (partial) chunk is bounded inclusively by the high-water
     * boundary.
     */
    @Test
    void processesAPartialLastChunk() {
        createSource(1, 2, 3, 4, 5);
        String label = label("partial");

        run(label, 2);

        assertEquals(5, doneCount(), "all five rows should be updated");
        long runId = runId(label);
        // chunks start at 1, 3, 5; terminal boundary at 5.
        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});
        assertEquals(3, completedBoundaries(runId),
                "the three chunk boundaries should be completed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * The final chunk uses an inclusive upper bound, so with an exact multiple
     * of the chunk size the maximum row is still processed.
     */
    @Test
    void finalChunkIncludesTheMaximumRow() {
        createSource(1, 2, 3, 4);
        String label = label("exact");

        run(label, 2);

        assertEquals(4, doneCount(), "the maximum row must be in the final chunk");
    }

    /**
     * An empty driving table produces a run with no boundaries, marked complete.
     */
    @Test
    void emptyDrivingTableCompletesImmediately() {
        createTestTable(SOURCE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        String label = label("empty");

        run(label, 4);

        long runId = runId(label);
        assertTrue(runCompleted(runId), "an empty run should be complete");
        assertEquals(0, completedBoundaries(runId), "an empty run has no boundaries");
    }

    /**
     * Re-running a completed label is a no-op: the SQL is not executed again.
     */
    @Test
    void completedRunIsANoOp() {
        createSource(1, 2, 3, 4);
        String label = label("noop");

        run(label, 2);
        dsl.execute("UPDATE " + SOURCE_QUALIFIED + " SET payload = 'touched'");

        run(label, 2);

        assertEquals(4, touchedCount(),
                "a completed run must not revert the rows (the chunk SQL must not run again)");
        assertEquals(0, doneCount(), "the chunk SQL must not run again on a completed run");
    }

    /**
     * A resume starts at the first unclaimed boundary; the rows of the
     * pre-completed chunk are not reprocessed.
     */
    @Test
    void resumesFromTheFirstUnclaimedBoundary() {
        createSource(1, 2, 3, 4);
        String label = label("resume");

        // Create the run and boundaries, then mark the first chunk complete by
        // hand to simulate a partially processed run.
        long runId = Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, "SELECT 1", 2);
        dsl.execute("UPDATE dml_utils.migration_boundary SET completed_at = pg_catalog.now()"
                + " WHERE run_id = ? AND boundary_no = 0", runId);

        run(label, 2);

        assertEquals(2, doneCount(), "only the unclaimed chunk's rows should be processed");
        assertEquals(2, completedBoundaries(runId), "both chunk boundaries should be complete");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * A chunk worker error propagates with its SQLSTATE and the run is left
     * incomplete so it can be retried.
     */
    @Test
    void propagatesChunkErrors() {
        createSource(1, 2, 3, 4);
        String label = label("error");

        assertSqlState("22012", () -> Routines.runMigrationChunks(
                dsl.configuration(),
                "UPDATE <driving_table> SET payload = (1 / 0)::text WHERE <chunking_clause>",
                PUBLIC_SCHEMA, SOURCE, label, 2, "t"));

        assertTrue(!runCompleted(runId(label)), "a failed run must not be marked complete");
    }

    /**
     * The primary-key column is resolved from the catalog, not assumed to be
     * named {@code id}: a table whose key is {@code key} still chunks correctly.
     */
    @Test
    void resolvesThePrimaryKeyColumnNameFromTheCatalog() {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "key bigint PRIMARY KEY, payload text");
        for (long id = 1; id <= 6; id++) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (key) VALUES (?)", id);
        }
        String label = label("catalog-pk");

        run(label, 2);

        assertEquals(6, doneCount(), "all rows should be processed using the catalog key");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * The alias argument defaults to {@code t} at the SQL level when omitted.
     */
    @Test
    void usesTheDefaultAliasWhenOmitted() {
        createSource(1, 2, 3, 4);
        String label = label("default-alias");

        dsl.execute(
                "SELECT dml_utils.run_migration_chunks("
                        + "?::dml_utils.non_null_text, ?::dml_utils.non_null_text,"
                        + " ?::dml_utils.non_null_text, ?::dml_utils.non_null_text,"
                        + " ?::dml_utils.positive_integer)",
                TEMPLATE, PUBLIC_SCHEMA, SOURCE, label, 2);

        assertEquals(4, doneCount(), "the default alias t should be used");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    private void createSource(long... ids) {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        for (long id : ids) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
    }

    private String label(String suffix) {
        return "run-chunks-" + suffix + "-" + System.nanoTime();
    }

    private void run(String label, int chunkSize) {
        Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PUBLIC_SCHEMA, SOURCE, label, chunkSize, "t");
    }

    private long runId(String label) {
        return dsl.fetchOne("SELECT run_id FROM dml_utils.migration_run WHERE label = ?", label)
                .get(0, Long.class);
    }

    private boolean runCompleted(long runId) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT completed_at IS NOT NULL FROM dml_utils.migration_run WHERE run_id = ?",
                runId));
    }

    private int completedBoundaries(long runId) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM dml_utils.migration_boundary"
                                + " WHERE run_id = ? AND completed_at IS NOT NULL", runId)
                .get(0, Integer.class);
    }

    private boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT completed_at IS NOT NULL FROM dml_utils.migration_boundary"
                        + " WHERE run_id = ? AND boundary_no = ?", runId, boundaryNo));
    }

    private void assertBoundaries(long runId, long[][] expected) {
        var actual = dsl.fetch(
                "SELECT boundary_no, boundary_id FROM dml_utils.migration_boundary"
                        + " WHERE run_id = ? ORDER BY boundary_no", runId);
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0],
                    actual.get(i).get("boundary_no", Long.class), "boundary_no " + i);
            assertEquals(expected[i][1],
                    actual.get(i).get("boundary_id", Long.class), "boundary_id " + i);
        }
    }

    private int doneCount() {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM " + SOURCE_QUALIFIED + " WHERE payload = 'done'")
                .get(0, Integer.class);
    }

    private int touchedCount() {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM " + SOURCE_QUALIFIED + " WHERE payload = 'touched'")
                .get(0, Integer.class);
    }
}
