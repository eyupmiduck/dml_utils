package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.routines.RunMigrationChunks;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationErrorRecord;
import org.jooq.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestInteger.TEST_INTEGER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestKey.TEST_KEY;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestSmallint.TEST_SMALLINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils.run_migration_chunks}: it processes every fixed-row
 * chunk of the driving table via pg_background workers, records run and
 * boundary completion, resumes from the first unclaimed boundary, and is a
 * no-op for an already-completed run.
 */
class RunMigrationChunksTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Clears the fixture tables this class drives a run over, so each test
     * starts from a known state.
     */
    @BeforeEach
    void resetFixtures() {
        for (Table<?> table : List.of(TEST_BIGINT, TEST_OTHER, TEST_INTEGER, TEST_SMALLINT,
                TEST_TEXT, TEST_UUID, TEST_KEY)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * Every row is processed, every chunk boundary is claimed, and the run is
     * marked complete.
     */
    @Test
    void processesAllChunksAndCompletesTheRun() {
        createSource(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = label("all");

        run(label, 4, TEST_BIGINT);

        assertEquals(10, doneCount(TEST_BIGINT), "all rows should be updated");
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

        run(label, 2, TEST_BIGINT);

        assertEquals(5, doneCount(TEST_BIGINT), "all five rows should be updated");
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

        run(label, 2, TEST_BIGINT);

        assertEquals(4, doneCount(TEST_BIGINT), "the maximum row must be in the final chunk");
    }

    /**
     * An empty driving table produces a run with no boundaries, marked complete.
     */
    @Test
    void emptyDrivingTableCompletesImmediately() {
        String label = label("empty");

        run(label, 4, TEST_BIGINT);

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

        run(label, 2, TEST_BIGINT);
        dsl.update(TEST_BIGINT).set(TEST_BIGINT.PAYLOAD, "touched").execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "touched"),
                "a completed run must not revert the rows (the chunk SQL must not run again)");
        assertEquals(0, doneCount(TEST_BIGINT), "the chunk SQL must not run again on a completed run");
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
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L)))
                .execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(2, doneCount(TEST_BIGINT), "only the unclaimed chunk's rows should be processed");
        assertEquals(2, completedBoundaries(runId), "both chunk boundaries should be complete");
        assertTrue(runCompleted(runId), "the run should be marked complete");
    }

    /**
     * The chunk/final classification comes from the run's full ordered boundary
     * set, not from the unclaimed subset: with the terminal high-water boundary
     * already completed, every real chunk is still processed and none is
     * misclassified as final.
     */
    @Test
    void classifiesChunksFromTheFullBoundarySetNotTheUnclaimedSet() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("terminal-claimed");

        // Boundaries for 6 rows at chunk size 2: 0, 1, 2, and terminal 3.
        long runId = populate(TEST_BIGINT, label, TEMPLATE, 2);
        // Complete the terminal boundary directly (it is not a chunk).
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(3L)))
                .execute();

        run(label, 2, TEST_BIGINT);

        assertEquals(6, doneCount(TEST_BIGINT), "every row must be processed; no chunk may be skipped");
        assertEquals(4, completedBoundaries(runId),
                "all three chunks and the terminal boundary should be complete");
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
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 2, "t"));

        long runId = runId(label);
        assertTrue(!runCompleted(runId), "a failed run must not be marked complete");

        // The failure is recorded in its own transaction, so the row survives
        // the re-raise that rolled the caller's transaction back.
        MigrationErrorRecord error = dsl.selectFrom(MIGRATION_ERROR)
                .where(MIGRATION_ERROR.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(error, "the failed chunk should be recorded");
        assertEquals(0L, error.getBoundaryNo(), "the first chunk should be the failure");
        assertEquals("22012", error.getSqlstate());
        assertTrue(error.getMessage().contains("division by zero"),
                () -> "unexpected message: " + error.getMessage());
    }

    /**
     * A pre-existing active run for the label is resumed rather than recreated:
     * no 23505 is raised and every chunk is processed against that run.
     */
    @Test
    void resumesAPreExistingActiveRun() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("active");

        long existingRun = populate(TEST_BIGINT, label, TEMPLATE, 2);

        run(label, 2, TEST_BIGINT);

        assertEquals(6, doneCount(TEST_BIGINT), "every chunk of the existing run should be processed");
        assertEquals(existingRun, runId(label), "the existing run should be reused");
        assertTrue(runCompleted(existingRun), "the reused run should be marked complete");
    }

    /**
     * A resumed run ignores a differing {@code i_chunk_size}: the boundaries (and
     * their chunk size) are fixed when the run was created.
     */
    @Test
    void resumeIgnoresADifferingChunkSize() {
        createSource(1, 2, 3, 4, 5, 6);
        String label = label("chunk-size-resume");

        // Stored chunk size 2 gives boundaries 0,1,2 and terminal 3.
        long existingRun = populate(TEST_BIGINT, label, TEMPLATE, 2);

        run(label, 10, TEST_BIGINT);

        assertEquals(3, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(existingRun)
                                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.lt(3L))),
                "the stored boundaries must be used, not recomputed for chunk size 10");
        assertEquals(6, doneCount(TEST_BIGINT), "every row of the stored chunks should be processed");
        assertTrue(runCompleted(existingRun), "the reused run should be marked complete");
    }

    /**
     * A resumed run uses the driving table recorded when the run was created: a
     * different input table is ignored, so the stored boundaries and the
     * rendered chunk SQL always refer to the same table.
     */
    @Test
    void resumeUsesTheStoredDrivingTable() {
        createSource(1, 2, 3, 4);
        createSource(TEST_OTHER, 1, 2, 3, 4);
        String label = label("stored-table");
        populate(TEST_BIGINT, label, TEMPLATE, 2);

        // A resumed call naming a different driving table must be ignored.
        run(label, 2, TEST_OTHER);

        assertEquals(4, doneCount(TEST_BIGINT), "the stored driving table should be processed");
        assertEquals(0, payloadCount(TEST_OTHER, "done"),
                "the input table must not be touched");
    }

    /**
     * On resume the stored {@code sql_text} is used and a differing input is
     * ignored; a resumed run whose stored SQL was adjusted via
     * {@code set_migration_run_sql_text} then uses the adjusted SQL.
     */
    @Test
    void resumeUsesTheStoredSqlText() {
        createSource(1, 2, 3, 4);
        String label = label("stored");

        // An unfinished run whose stored SQL writes 'first'.
        populate(TEST_BIGINT, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);

        // A resumed call with a different input must ignore the input.
        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>",
                2, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "first"), "the stored SQL should be used on resume");
        assertEquals(0, payloadCount(TEST_BIGINT, "ignored"),
                "the caller's differing SQL should be ignored");
    }

    /**
     * A resumed run whose stored SQL was changed via
     * {@code set_migration_run_sql_text} uses the new SQL.
     */
    @Test
    void setMigrationRunSqlTextChangesTheResumedSql() {
        createSource(1, 2, 3, 4);
        String label = label("set-sql");

        // Create an unfinished run (populate only), then adjust its SQL.
        populate(TEST_BIGINT, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);
        Routines.setMigrationRunSqlText(dsl.configuration(), label,
                "UPDATE <driving_table> SET payload = 'second' WHERE <chunking_clause>");

        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>",
                2, TEST_BIGINT);

        assertEquals(4, payloadCount(TEST_BIGINT, "second"), "the adjusted (stored) SQL should be used");
        assertEquals(0, payloadCount(TEST_BIGINT, "first"), "the original stored SQL should not be used");
    }

    /**
     * {@code set_migration_run_sql_text} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunSqlTextRaisesWhenNoUnfinishedRun() {
        assertSqlState("P0002", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), "no-such-label", TEMPLATE));
    }

    /**
     * {@code set_migration_run_sql_text} rejects a value that is not a valid
     * chunking template with {@code 22023}.
     */
    @Test
    void setMigrationRunSqlTextRejectsAnInvalidTemplate() {
        createSource(1, 2, 3, 4);
        String label = label("invalid-template");
        populate(TEST_BIGINT, label, TEMPLATE, 2);

        assertSqlState("22023", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), label, "UPDATE <driving_table> SET x = 1"));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), null, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, "t"));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "   ", 2, "t"));
    }

    /**
     * A non-positive chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 0, "t"));
    }

    /**
     * A null alias is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullAlias() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), "l", 2, null));
    }

    /**
     * The primary-key column is resolved from the catalog, not assumed to be
     * named {@code id}: a table whose key is {@code key} still chunks correctly.
     */
    @Test
    void resolvesThePrimaryKeyColumnNameFromTheCatalog() {
        for (long id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_KEY, TEST_KEY.KEY).values(id).execute();
        }
        String label = label("catalog-pk");

        run(label, 2, TEST_KEY);

        assertEquals(6, doneCount(TEST_KEY), "all rows should be processed using the catalog key");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * An {@code integer} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithAnIntegerPrimaryKey() {
        for (int id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_INTEGER, TEST_INTEGER.ID).values(id).execute();
        }
        String label = label("integer-pk");

        run(label, 2, TEST_INTEGER);

        assertEquals(6, doneCount(TEST_INTEGER), "every row of an integer-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A {@code smallint} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithASmallintPrimaryKey() {
        for (int id = 1; id <= 6; id++) {
            dsl.insertInto(TEST_SMALLINT, TEST_SMALLINT.ID).values((short) id).execute();
        }
        String label = label("smallint-pk");

        run(label, 2, TEST_SMALLINT);

        assertEquals(6, doneCount(TEST_SMALLINT), "every row of a smallint-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A table with a text primary key is chunked end to end, including a key
     * that contains a quote (the rendered literal must escape it).
     */
    @Test
    void processesATableWithATextPrimaryKey() {
        for (String id : new String[]{"a", "b", "o'brien", "z"}) {
            dsl.insertInto(TEST_TEXT, TEST_TEXT.ID).values(id).execute();
        }
        String label = label("text-pk");

        run(label, 2, TEST_TEXT);

        long runId = runId(label);
        assertEquals(4, doneCount(TEST_TEXT), "every row of a text-keyed table should be processed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
        assertEquals(List.of("a", "o'brien", "z"), textBoundaryValues(runId),
                "the stored boundaries should hold the chunk start keys and the high-water key");
    }

    /**
     * A table with a uuid primary key is chunked end to end, even though
     * PostgreSQL has no {@code min}/{@code max} aggregate for uuid.
     */
    @Test
    void processesATableWithAUuidPrimaryKey() {
        for (int i = 1; i <= 6; i++) {
            dsl.insertInto(TEST_UUID, TEST_UUID.ID).values(uuid(i)).execute();
        }
        String label = label("uuid-pk");

        run(label, 2, TEST_UUID);

        long runId = runId(label);
        assertEquals(6, doneCount(TEST_UUID), "every row of a uuid-keyed table should be processed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
        assertEquals(
                List.of(uuid(1), uuid(3), uuid(5), uuid(6)),
                uuidBoundaryValues(runId),
                "the stored boundaries should hold the chunk start keys and the high-water key");
    }

    /**
     * The alias argument defaults to {@code t} at the SQL level when omitted.
     */
    @Test
    void usesTheDefaultAliasWhenOmitted() {
        createSource(1, 2, 3, 4);
        String label = label("default-alias");

        RunMigrationChunks routine = runRoutine(label);
        routine.setIChunkSize(2);
        routine.execute(dsl.configuration());

        assertEquals(4, doneCount(TEST_BIGINT), "the default alias t should be used");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * The chunk-size argument defaults to 1000 at the SQL level when omitted.
     */
    @Test
    void usesTheDefaultChunkSizeWhenOmitted() {
        createSource(1, 2, 3, 4);
        String label = label("default-chunk-size");

        runRoutine(label).execute(dsl.configuration());

        assertEquals(4, doneCount(TEST_BIGINT), "every row should be processed with the default chunk size");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * Builds a {@code run_migration_chunks} call with the required arguments
     * set, leaving the defaulted ones for jOOQ to omit.
     */
    private RunMigrationChunks runRoutine(String label) {
        RunMigrationChunks routine = new RunMigrationChunks();
        routine.setISqlText(TEMPLATE);
        routine.setIDrivingTableSchemaName(schema(TEST_BIGINT));
        routine.setIDrivingTableName(name(TEST_BIGINT));
        routine.setILabel(label);
        return routine;
    }

    /**
     * Returns the run's boundary keys in order as their text attribute.
     */
    private List<String> textBoundaryValues(long runId) {
        return dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .stream()
                .map(key -> key.getTextValue())
                .toList();
    }

    /**
     * Returns the run's boundary keys in order as their uuid attribute.
     */
    private List<UUID> uuidBoundaryValues(long runId) {
        return dsl.select(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch(MIGRATION_BOUNDARY.BOUNDARY_ID)
                .stream()
                .map(key -> key.getUuidValue())
                .toList();
    }

    /**
     * Builds the ordered uuid used for source key {@code n}.
     */
    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n));
    }

    private void createSource(long... ids) {
        createSource(TEST_BIGINT, ids);
    }

    private void createSource(Table<?> table, long... ids) {
        for (long id : ids) {
            dsl.insertInto(table, field("id", Long.class)).values(id).execute();
        }
    }

    private long populate(Table<?> table, String label, String template, int chunkSize) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(table), name(table), label, template, chunkSize);
    }

    private String label(String suffix) {
        return "run-chunks-" + suffix + "-" + System.nanoTime();
    }

    private void run(String label, int chunkSize, Table<?> table) {
        runWith(label, TEMPLATE, chunkSize, table);
    }

    private void runWith(String label, String template, int chunkSize, Table<?> table) {
        Routines.runMigrationChunks(
                dsl.configuration(), template, schema(table), name(table), label, chunkSize, "t");
    }

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

    private long runId(String label) {
        return dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    private boolean runCompleted(long runId) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    private int completedBoundaries(long runId) {
        return dsl.fetchCount(MIGRATION_BOUNDARY,
                MIGRATION_BOUNDARY.RUN_ID.eq(runId).and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    private boolean boundaryCompleted(long runId, long boundaryNo) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())
                .from(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(boundaryNo)))
                .fetchOne(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull()));
    }

    private void assertBoundaries(long runId, long[][] expected) {
        List<MigrationBoundaryRecord> actual = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();
        assertEquals(expected.length, actual.size(), "boundary count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i).getBoundaryNo().longValue(),
                    "boundary_no " + i);
            assertEquals(expected[i][1], actual.get(i).getBoundaryId().getBigintValue().longValue(),
                    "boundary_id " + i);
        }
    }

    private int doneCount(Table<?> table) {
        return payloadCount(table, "done");
    }

    private int payloadCount(Table<?> table, String value) {
        return dsl.selectCount()
                .from(table)
                .where(field("payload", String.class).eq(value))
                .fetchOne(0, Integer.class);
    }
}
