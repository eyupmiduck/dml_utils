package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that a cancellation reaches a run whose coordinator is already
 * processing chunks: the coordinator stops launching, cancels the in-flight
 * workers, records the cancellation and returns.
 */
class RunMigrationChunksCancellationTest extends PostgresTestBase {

    /**
     * One row sleeps for a second, so the chunks are still in flight when the test
     * cancels; this makes the cancellation deterministic without relying on a
     * particular chunk duration.
     */
    private static final String SLOW_TEMPLATE =
            "SELECT pg_sleep(1) FROM <driving_table> WHERE <chunking_clause>";

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

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
     * A cancellation from another session stops the run, cancels the in-flight
     * chunks (so no further chunk commits) and leaves the run unfinished.
     */
    @Test
    void cancelsAnInFlightRunAndStopsItsWorkers() throws Exception {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("cancel-in-flight");

        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try (Connection owner = openOwnerConnection()) {
                DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
                Routines.runMigrationChunks(ownerDsl.configuration(), SLOW_TEMPLATE,
                        schema(TEST_BIGINT), name(TEST_BIGINT), label, 5, 2, "t",
                        ChunkingStrategy.primary_key, 1);
            } catch (SQLException error) {
                throw new CompletionException(error);
            }
        });

        long runId = awaitRunWithBoundaries(label);
        Thread.sleep(200);
        Long cancelled = Routines.cancelMigrationRun(dsl.configuration(), label);
        assertEquals(runId, cancelled, "the active run is cancelled");

        run.get(30, TimeUnit.SECONDS);

        MigrationRunRecord record = dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.RUN_ID.eq(runId))
                .fetchOne();
        assertNotNull(record.getCancelledAt(), "the run is cancelled");
        assertNull(record.getCompletedAt(), "the cancelled run did not complete");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.COMPLETED_AT.isNotNull())),
                "an in-flight chunk is cancelled, not completed");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY,
                        MIGRATION_BOUNDARY.RUN_ID.eq(runId)
                                .and(MIGRATION_BOUNDARY.CANCELLED_AT.isNull())),
                "the remaining boundaries are marked cancelled");
    }

    /**
     * Waits until the background call has created the run and its boundaries.
     *
     * @param label the run label
     * @return the run id
     * @throws InterruptedException if the wait is interrupted
     */
    private long awaitRunWithBoundaries(String label) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Record row = dsl.fetchOne(
                    "SELECT r.run_id FROM dml_utils_data.migration_run AS r"
                            + " WHERE r.label = ?"
                            + " AND EXISTS (SELECT 1 FROM dml_utils_data.migration_boundary AS b"
                            + " WHERE b.run_id = r.run_id)",
                    label);
            if (row != null) {
                return row.get(0, Long.class);
            }
            Thread.sleep(50);
        }
        fail("the run did not create boundaries");
        return -1;
    }
}
