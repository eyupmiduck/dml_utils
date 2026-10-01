package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.MigrationErrorRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.MigrationRun.MIGRATION_RUN;
import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * Builds the ordered uuid used for source key {@code n}.
     */
    private static UUID uuid(int n) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", n));
    }

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
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(0L)))
                .execute();

        run(label, 2);

        assertEquals(2, doneCount(), "only the unclaimed chunk's rows should be processed");
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
        long runId = Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);
        // Complete the terminal boundary directly (it is not a chunk).
        dsl.update(MIGRATION_BOUNDARY)
                .set(MIGRATION_BOUNDARY.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                        .and(MIGRATION_BOUNDARY.BOUNDARY_NO.eq(3L)))
                .execute();

        run(label, 2);

        assertEquals(6, doneCount(), "every row must be processed; no chunk may be skipped");
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
                PUBLIC_SCHEMA, SOURCE, label, 2, "t"));

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

        long existingRun = Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);

        run(label, 2);

        assertEquals(6, doneCount(), "every chunk of the existing run should be processed");
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
        long existingRun = Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);

        run(label, 10);

        assertEquals(3, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(existingRun)
                                .and(MIGRATION_BOUNDARY.BOUNDARY_NO.lt(3L))),
                "the stored boundaries must be used, not recomputed for chunk size 10");
        assertEquals(6, doneCount(), "every row of the stored chunks should be processed");
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
        String other = "run_chunks_other";
        String otherQualified = PUBLIC_SCHEMA + "." + other;
        dropTestTable(otherQualified);
        createTestTable(otherQualified, "id bigint PRIMARY KEY, payload text");
        for (long id = 1; id <= 4; id++) {
            dsl.execute("INSERT INTO " + otherQualified + " (id) VALUES (?)", id);
        }

        String label = label("stored-table");
        Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);

        // A resumed call naming a different driving table must be ignored.
        Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PUBLIC_SCHEMA, other, label, 2, "t");

        assertEquals(4, doneCount(), "the stored driving table should be processed");
        assertEquals(0, payloadCount(otherQualified, "done"),
                "the input table must not be touched");

        dropTestTable(otherQualified);
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
        Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);

        // A resumed call with a different input must ignore the input.
        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>", 2);

        assertEquals(4, firstCount(), "the stored SQL should be used on resume");
        assertEquals(0, ignoredCount(), "the caller's differing SQL should be ignored");
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
        Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label,
                "UPDATE <driving_table> SET payload = 'first' WHERE <chunking_clause>", 2);
        Routines.setMigrationRunSqlText(dsl.configuration(), label,
                "UPDATE <driving_table> SET payload = 'second' WHERE <chunking_clause>");

        runWith(label, "UPDATE <driving_table> SET payload = 'ignored' WHERE <chunking_clause>", 2);

        assertEquals(4, secondCount(), "the adjusted (stored) SQL should be used");
        assertEquals(0, firstCount(), "the original stored SQL should not be used");
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
        Routines.populateMigrationBoundaries(
                dsl.configuration(), PUBLIC_SCHEMA, SOURCE, label, TEMPLATE, 2);

        assertSqlState("22023", () -> Routines.setMigrationRunSqlText(
                dsl.configuration(), label, "UPDATE <driving_table> SET x = 1"));
    }

    /**
     * A null SQL text is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullSqlText() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), null, PUBLIC_SCHEMA, SOURCE, "l", 2, "t"));
    }

    /**
     * A blank label is rejected by the non-null text domain.
     */
    @Test
    void rejectsBlankLabel() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PUBLIC_SCHEMA, SOURCE, "   ", 2, "t"));
    }

    /**
     * A non-positive chunk size is rejected by the positive-integer domain.
     */
    @Test
    void rejectsNonPositiveChunkSize() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PUBLIC_SCHEMA, SOURCE, "l", 0, "t"));
    }

    /**
     * A null alias is rejected by the non-null text domain.
     */
    @Test
    void rejectsNullAlias() {
        assertDomainViolation(() -> Routines.runMigrationChunks(
                dsl.configuration(), TEMPLATE, PUBLIC_SCHEMA, SOURCE, "l", 2, null));
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
     * An {@code integer} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithAnIntegerPrimaryKey() {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id integer PRIMARY KEY, payload text");
        for (int id = 1; id <= 6; id++) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
        String label = label("integer-pk");

        run(label, 2);

        assertEquals(6, doneCount(), "every row of an integer-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A {@code smallint} (non-bigint) primary key is supported end to end.
     */
    @Test
    void processesATableWithASmallintPrimaryKey() {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id smallint PRIMARY KEY, payload text");
        for (int id = 1; id <= 6; id++) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
        String label = label("smallint-pk");

        run(label, 2);

        assertEquals(6, doneCount(), "every row of a smallint-keyed table should be processed");
        assertTrue(runCompleted(runId(label)), "the run should be marked complete");
    }

    /**
     * A table with a text primary key is chunked end to end, including a key
     * that contains a quote (the rendered literal must escape it).
     */
    @Test
    void processesATableWithATextPrimaryKey() {
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id text PRIMARY KEY, payload text");
        for (String id : new String[]{"a", "b", "o'brien", "z"}) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?)", id);
        }
        String label = label("text-pk");

        run(label, 2);

        long runId = runId(label);
        assertEquals(4, doneCount(), "every row of a text-keyed table should be processed");
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
        dropTestTable(SOURCE_QUALIFIED);
        createTestTable(SOURCE_QUALIFIED, "id uuid PRIMARY KEY, payload text");
        for (int i = 1; i <= 6; i++) {
            dsl.execute("INSERT INTO " + SOURCE_QUALIFIED + " (id) VALUES (?::uuid)",
                    "00000000-0000-0000-0000-" + String.format("%012d", i));
        }
        String label = label("uuid-pk");

        run(label, 2);

        long runId = runId(label);
        assertEquals(6, doneCount(), "every row of a uuid-keyed table should be processed");
        assertTrue(runCompleted(runId), "the run should be marked complete");
        assertEquals(
                List.of(uuid(1), uuid(3), uuid(5), uuid(6)),
                uuidBoundaryValues(runId),
                "the stored boundaries should hold the chunk start keys and the high-water key");
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
        runWith(label, TEMPLATE, chunkSize);
    }

    private void runWith(String label, String template, int chunkSize) {
        Routines.runMigrationChunks(
                dsl.configuration(), template, PUBLIC_SCHEMA, SOURCE, label, chunkSize, "t");
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

    private int doneCount() {
        return payloadCount("done");
    }

    private int touchedCount() {
        return payloadCount("touched");
    }

    private int firstCount() {
        return payloadCount("first");
    }

    private int secondCount() {
        return payloadCount("second");
    }

    private int ignoredCount() {
        return payloadCount("ignored");
    }

    private int payloadCount(String value) {
        return payloadCount(SOURCE_QUALIFIED, value);
    }

    private int payloadCount(String qualifiedTable, String value) {
        return dsl.fetchOne(
                        "SELECT count(*)::int FROM " + qualifiedTable + " WHERE payload = ?",
                        value)
                .get(0, Integer.class);
    }
}
