package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@code dml_utils.archive_migration_run}: it cancels the active run
 * before archiving it, stamps the archive after the cancellation, archives the
 * run that is active for the label (not a later one), and returns NULL when
 * there is nothing active to archive.
 */
class ArchiveMigrationRunTest extends PostgresTestBase {

    /**
     * Empties the migration metadata and the bigint fixture between tests.
     */
    @BeforeEach
    void reset() {
        resetRunsAndBigint();
    }

    /**
     * An unfinished run is cancelled and then archived, and the archive
     * timestamp is not earlier than the cancellation it performed.
     */
    @Test
    void archivesTheActiveRunAfterCancellingIt() {
        String label = uniqueLabel("archive");
        long runId = populateSingleRelation(label, "SELECT 1", 1);

        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getArchivedAt(), "the run is archived");
        assertNotNull(run.getCancelledAt(), "an unfinished run is cancelled first");
        assertFalse(run.getArchivedAt().isBefore(run.getCancelledAt()),
                "archived_at must not precede cancelled_at");
    }

    /**
     * A run that was already cancelled is archived without moving its
     * cancellation timestamp, so the inner cancel is a no-op.
     */
    @Test
    void archivesAnAlreadyCancelledRunWithoutMovingTheTimestamp() {
        String label = uniqueLabel("archive-cancelled");
        long runId = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(runId, Routines.cancelMigrationRun(dsl.configuration(), label));
        OffsetDateTime cancelled = migrationRun(runId).getCancelledAt();
        assertNotNull(cancelled);

        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));

        MigrationRunRecord run = migrationRun(runId);
        assertNotNull(run.getArchivedAt());
        assertEquals(cancelled, run.getCancelledAt(),
                "the existing cancellation timestamp is kept");
        assertFalse(run.getArchivedAt().isBefore(cancelled));
    }

    /**
     * Archiving a label, then creating a new run with the same label, archives
     * each run separately: the stale archive does not touch the newer run.
     */
    @Test
    void archivesOnlyTheRunActiveForTheLabel() {
        String label = uniqueLabel("archive-recreate");
        long first = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(first, Routines.archiveMigrationRun(dsl.configuration(), label));
        OffsetDateTime firstArchived = migrationRun(first).getArchivedAt();
        assertNotNull(firstArchived);

        long second = populateSingleRelation(label, "SELECT 1", 1);
        assertNotEquals(first, second, "archiving the label allows a new run");
        assertEquals(second, Routines.archiveMigrationRun(dsl.configuration(), label));

        assertEquals(firstArchived, migrationRun(first).getArchivedAt(),
                "the first run's archive is untouched");
        assertNotNull(migrationRun(second).getArchivedAt(),
                "the second run is archived");
    }

    /**
     * A missing active run returns NULL, and a second archive of the same label
     * has nothing left to archive.
     */
    @Test
    void returnsNullWhenThereIsNoActiveRun() {
        assertNull(Routines.archiveMigrationRun(
                dsl.configuration(), uniqueLabel("archive-none")));

        String label = uniqueLabel("archive-twice");
        long runId = populateSingleRelation(label, "SELECT 1", 1);
        assertEquals(runId, Routines.archiveMigrationRun(dsl.configuration(), label));
        assertNull(Routines.archiveMigrationRun(dsl.configuration(), label),
                "the archived run is not active any more");
    }
}
