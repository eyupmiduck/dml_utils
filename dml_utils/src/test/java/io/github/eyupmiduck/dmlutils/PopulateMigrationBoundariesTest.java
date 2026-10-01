package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.populate_migration_boundaries}: it records a
 * migration run and one fixed-row chunk boundary per chunk, ending with a
 * terminal high-water boundary at the captured maximum primary key.
 */
class PopulateMigrationBoundariesTest extends PostgresTestBase {

    private static final String SOURCE = "migration_boundary_source";
    private static final String SOURCE_QUALIFIED = PUBLIC_SCHEMA + "." + SOURCE;
    private static final String LABEL = "boundary-test-run";
    private static final String SQL_TEXT = "SELECT 1";

    /**
     * Each test gets a distinct label so the one-active-run-per-label rule does
     * not couple tests that populate in the same database.
     */
    private int labelCounter;

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
        long secondRun = populate(LABEL + "-" + ++labelCounter, 3);

        assertNotEquals(firstRun, secondRun, "each call creates a new run");
        assertBoundaries(firstRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
        assertBoundaries(secondRun, new long[][]{{0, 1}, {1, 4}, {2, 4}});
    }

    /**
     * The run records the supplied label, SQL text and chunk size.
     */
    @Test
    void recordsLabelSqlTextAndChunkSize() {
        createSource(1, 2, 3, 4, 5);

        String label = "records-columns-run";
        long runId = populate(label, 5);

        MigrationRunRecord run = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertEquals(label, run.getLabel());
        assertEquals(SQL_TEXT, run.getSqlText());
        assertEquals(5, run.getChunkSize());
    }

    /**
     * Boundaries are inserted with {@code completed_at} left null.
     */
    @Test
    void boundariesHaveNullCompletedAt() {
        createSource(1, 2, 3, 4);

        long runId = populate(2);

        int completed = dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId).and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
        assertEquals(0, completed, "completed_at should be null for a fresh run");
    }

    /**
     * The primary-key column is resolved from the catalog, not assumed to be
     * named {@code id}.
     */
    @Test
    void resolvesThePrimaryKeyColumnNameFromTheCatalog() {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "key bigint PRIMARY KEY, payload text");
        dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (key) VALUES (1)");
        dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (key) VALUES (2)");
        dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (key) VALUES (3)");

        long runId = populate(2);

        assertBoundaries(runId, new long[][]{{0, 1}, {1, 3}, {2, 3}});
    }

    /**
     * A label can be reused by archiving its active run: populate, archive,
     * then populate with the same label again. The first run keeps its
     * boundaries and gets an {@code archived_at}; the second is a new row.
     */
    @Test
    void labelCanBeReusedAfterArchiving() {
        createSource(1, 2, 3, 4);

        String label = LABEL + "-" + ++labelCounter;
        long firstRun = populate(label, 2);

        Long archivedId = Routines.archiveMigrationRun(dsl.configuration(), label);

        long secondRun = populate(label, 2);

        assertEquals(firstRun, archivedId, "archive should return the archived run id");
        assertNotEquals(firstRun, secondRun, "a new run should be created");
        assertTrue(archived(firstRun), "the first run should be archived");
        assertFalse(archived(secondRun), "the second run should be active");
        assertBoundaries(firstRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
        assertBoundaries(secondRun, new long[][]{{0, 1}, {1, 3}, {2, 4}});
    }

    /**
     * Archiving a label with no active run returns null.
     */
    @Test
    void archivingAnUnknownLabelReturnsNull() {
        createSource(1, 2);

        assertNull(Routines.archiveMigrationRun(dsl.configuration(), "no-such-label"));
    }

    private void createSource(long... ids) {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id bigint PRIMARY KEY, payload text");
        for (long id : ids) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
    }

    private boolean archived(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.ARCHIVED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.ARCHIVED_AT.isNotNull()));
    }

    private long populate(int chunkSize) {
        return populate(LABEL + "-" + ++labelCounter, chunkSize);
    }

    private long populate(String label, int chunkSize) {
        return Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, SQL_TEXT, chunkSize);
    }

    private List<MigrationBoundaryRecord> boundaries(long runId) {
        return dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
    }

    private void assertBoundaries(long runId, long[][] expected) {
        List<MigrationBoundaryRecord> actual = boundaries(runId);
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i).getBoundaryNo().longValue(),
                    "boundary_no " + i);
            assertEquals(expected[i][1], actual.get(i).getBoundaryId().longValue(),
                    "boundary_id " + i);
        }
    }

    private int runCount(long runId) {
        return dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.RUN_ID.eq(runId));
    }
}
