package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@code dml_utils.pause_migration_run} and
 * {@code dml_utils.resume_migration_run}: pause is a reversible, non-terminal
 * flag on the active unfinished run, terminal runs are not paused, and cancel or
 * completion clears the flag.
 */
class PauseMigrationRunTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = payload WHERE <chunking_clause>";

    /**
     * Clears the fixture table and the migration metadata so each test starts
     * clean.
     */
    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * Pausing an active run records the pause and leaves the run unfinished.
     */
    @Test
    void pausesAnActiveRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("pause");
        Long runId = populate(label);

        Long paused = Routines.pauseMigrationRun(dsl.configuration(), label);

        assertEquals(runId, paused, "pause returns the active run id");
        MigrationRunRecord run = run(runId);
        assertNotNull(run.getPausedAt(), "the run is paused");
        assertNull(run.getCompletedAt(), "a paused run is not completed");
        assertNull(run.getCancelledAt(), "a paused run is not cancelled");
    }

    /**
     * A second pause is idempotent and does not move the original timestamp.
     */
    @Test
    void pausingTwiceKeepsTheOriginalTimestamp() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("pause-twice");
        Long runId = populate(label);

        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));
        OffsetDateTime first = run(runId).getPausedAt();
        assertEquals(runId, Routines.pauseMigrationRun(dsl.configuration(), label));

        assertEquals(first, run(runId).getPausedAt(), "a second pause is a no-op");
    }

    /**
     * Resuming clears the pause and returns the run id.
     */
    @Test
    void resumesAPausedRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resume");
        Long runId = populate(label);
        assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label));

        assertEquals(runId, Routines.resumeMigrationRun(dsl.configuration(), label));

        assertNull(run(runId).getPausedAt(), "resume clears the pause");
    }

    /**
     * Resuming an active run that is not paused is a no-op and still returns the
     * run id.
     */
    @Test
    void resumingANotPausedRunReturnsItsId() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resume-not-paused");
        Long runId = populate(label);

        assertEquals(runId, Routines.resumeMigrationRun(dsl.configuration(), label));

        assertNull(run(runId).getPausedAt(), "the run was never paused");
    }

    /**
     * Pause and resume return NULL when there is no active unfinished run.
     */
    @Test
    void returnsNullWhenThereIsNoActiveRun() {
        String label = uniqueLabel("pause-none");

        assertNull(Routines.pauseMigrationRun(dsl.configuration(), label));
        assertNull(Routines.resumeMigrationRun(dsl.configuration(), label));
    }

    /**
     * A completed run is not pausable or resumable.
     */
    @Test
    void doesNotPauseACompletedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-completed");
        runToCompletion(label);
        Long runId = runIdForLabel(label);
        assertNotNull(run(runId).getCompletedAt(), "the run completed");

        assertNull(Routines.pauseMigrationRun(dsl.configuration(), label));
        assertNull(Routines.resumeMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getPausedAt(), "the completed run stays unpaused");
    }

    /**
     * A cancelled run is not pausable or resumable.
     */
    @Test
    void doesNotPauseACancelledRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-cancelled");
        Long runId = populate(label);
        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        assertNull(Routines.pauseMigrationRun(dsl.configuration(), label));
        assertNull(Routines.resumeMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getPausedAt(),
                "a cancelled run is not paused");
    }

    /**
     * An archived run is not pausable or resumable.
     */
    @Test
    void doesNotPauseAnArchivedRun() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-archived");
        Long runId = populate(label);
        assertNotNull(Routines.archiveMigrationRun(dsl.configuration(), label));

        assertNull(Routines.pauseMigrationRun(dsl.configuration(), label));
        assertNull(Routines.resumeMigrationRun(dsl.configuration(), label));
        assertNull(run(runId).getPausedAt(),
                "an archived run is not paused");
    }

    /**
     * Cancelling a paused run clears the pause and records the cancellation.
     */
    @Test
    void cancelClearsAPause() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-cancel");
        Long runId = populate(label);
        assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label));

        assertNotNull(Routines.cancelMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCancelledAt(), "the run is cancelled");
        assertNull(run.getPausedAt(), "cancel clears the pause");
    }

    /**
     * Completing a paused run clears the pause and records completion.
     */
    @Test
    void completeClearsAPause() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("pause-complete");
        Long runId = populate(label);
        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.TOTAL_CHUNKS_COMPLETED, MIGRATION_RUN.TOTAL_CHUNKS)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .execute();
        assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label));

        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.completeMigrationRun(
                dsl.configuration(), runId);

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getCompletedAt(), "the run is completed");
        assertNull(run.getPausedAt(), "completion clears the pause");
    }

    /**
     * Archiving a paused run cancels it, which clears the pause.
     */
    @Test
    void archiveClearsAPause() {
        seedBigint(1, 2);
        String label = uniqueLabel("pause-archive");
        Long runId = populate(label);
        assertNotNull(Routines.pauseMigrationRun(dsl.configuration(), label));

        assertNotNull(Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = run(runId);
        assertNotNull(run.getArchivedAt(), "the run is archived");
        assertNotNull(run.getCancelledAt(), "archiving cancels the unfinished run");
        assertNull(run.getPausedAt(), "archive clears the pause");
    }

    /**
     * Creates the run and its boundaries without processing any chunk.
     *
     * @param label the run label
     * @return the new run id
     */
    private Long populate(String label) {
        return io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_BIGINT), name(TEST_BIGINT), label,
                TEMPLATE, 2, 1, ChunkingStrategy.primary_key, "t", 0, null, false);
    }

    /**
     * Runs the migration to completion.
     *
     * @param label the run label
     */
    private void runToCompletion(String label) {
        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), label, 1, 1, "t",
                ChunkingStrategy.primary_key, 1, 0, null, false);
    }

    /**
     * Finds the run id for a label.
     *
     * @param label the run label
     * @return the run id
     */
    private Long runIdForLabel(String label) {
        return dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }

    /**
     * Reads the run row.
     *
     * @param runId the run id
     * @return the run record
     */
    private MigrationRunRecord run(Long runId) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
    }
}
