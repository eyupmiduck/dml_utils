package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.populate_migration_boundaries}: it records a
 * migration run and one fixed-row chunk boundary per chunk, ending with a
 * terminal high-water boundary at the captured maximum primary key.
 */
class PopulateMigrationBoundariesTest extends PostgresTestBase {

    private static final String SOURCE = "migration_boundary_source";
    private static final String SOURCE_QUALIFIED = PUBLIC_SCHEMA + "." + SOURCE;

    @AfterEach
    void dropSource() {
        dropTestTable(SOURCE_QUALIFIED);
    }

    /**
     * An empty source table still creates a run, but no boundaries.
     */
    @Test
    void emptySourceCreatesRunWithNoBoundaries() {
        createSource();

        long runId = populate(10);

        assertTrue(runId > 0, "a run id should be returned");
        assertEquals(1, runCount(runId), "the run row should exist");
        assertTrue(boundaries(runId).isEmpty(), "an empty source has no boundaries");
    }

    /**
     * A full table produces one boundary per chunk start plus the terminal
     * boundary at the maximum key.
     */
    @Test
    void createsAStartingBoundaryPerChunkAndATerminalBoundary() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        long runId = populate(4);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 5}, {2, 9}, {3, 10}});
    }

    /**
     * Chunks are cut by row number, not by key range, so gaps in the key
     * sequence do not change the boundary count.
     */
    @Test
    void chunksAreCutByRowNumberNotKeyRange() {
        createSource(1, 2, 5, 8, 10, 11, 15, 20, 24, 30);

        long runId = populate(4);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 10}, {2, 24}, {3, 30}});
    }

    /**
     * A single-row source produces one chunk plus the terminal boundary, both
     * at that row's key.
     */
    @Test
    void singleRowProducesChunkAndTerminalBoundary() {
        createSource(42);

        long runId = populate(10);

        assertBoundaries(runId, new long[][]{{0, 42}, {1, 42}});
    }

    /**
     * A chunk size of one makes every row a chunk start, with the terminal
     * boundary repeating the maximum.
     */
    @Test
    void chunkSizeOneStartsEveryRow() {
        createSource(1, 2, 3);

        long runId = populate(1);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 2}, {2, 3}, {3, 3}});
    }

    /**
     * A chunk size larger than the source produces a single chunk covering all
     * rows plus the terminal boundary.
     */
    @Test
    void chunkLargerThanSourceProducesOneChunk() {
        createSource(1, 2, 3);

        long runId = populate(10);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}});
    }

    /**
     * When the row count is an exact multiple of the chunk size the last chunk
     * still gets a starting boundary, ending on the maximum key.
     */
    @Test
    void exactMultipleOfChunkSizeEndsOnTheMaximum() {
        createSource(1, 2, 3, 4);

        long runId = populate(2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 4}});
    }

    /**
     * Boundaries are captured at population time, so keys inserted afterwards
     * do not change an existing run's boundaries or extend its terminal range.
     */
    @Test
    void laterInsertsDoNotChangeCapturedBoundaries() {
        createSource(1, 2, 3, 4, 5);

        long runId = populate(2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});

        dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (100)");

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 5}, {3, 5}});
    }

    /**
     * Two runs over the same source are independent and keep their own
     * boundaries.
     */
    @Test
    void multipleRunsAreIndependent() {
        createSource(1, 2, 3, 4);

        long firstRun = populate(2);
        long secondRun = populate(3);

        assertNotEquals(firstRun, secondRun, "each call creates a new run");
        assertBoundaries(firstRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
        assertBoundaries(secondRun, new long[][]{{0, 1}, {1, 4}, {2, 4}});
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
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, chunkSize);
    }

    private List<Record> boundaries(long runId) {
        return dsl.fetch(
                "SELECT boundary_no, boundary_id"
                        + " FROM dml_utils.migration_boundary"
                        + " WHERE run_id = ?"
                        + " ORDER BY boundary_no",
                runId);
    }

    private void assertBoundaries(long runId, long[][] expected) {
        List<Record> actual = boundaries(runId);
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0],
                    actual.get(i).get("boundary_no", Long.class).longValue(), "boundary_no " + i);
            assertEquals(expected[i][1],
                    actual.get(i).get("boundary_id", Long.class).longValue(), "boundary_id " + i);
        }
    }

    private int runCount(long runId) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM dml_utils.migration_run WHERE run_id = ?", runId)
                .get(0, Integer.class);
    }
}
