package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.ResolveMigrationRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@code dml_utils_lib.resolve_migration_run}: it creates a run for a
 * new label (populating its boundaries) and returns the effective inputs, reuses
 * an active run ignoring differing inputs, reports an already-completed run, and
 * rejects a thread count over {@code max_worker_processes}.
 */
class ResolveMigrationRunTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * Resets the run metadata and the fixture table between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * A new label creates a run, records its start, and returns the effective
     * inputs and resolved primary key.
     */
    @Test
    void createsANewRunAndReturnsTheEffectiveInputs() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-new");

        ResolveMigrationRun resolved = resolve(label, TEMPLATE, "t", 2, 1);

        assertNotNull(resolved.getORunId());
        assertNotEquals(Boolean.TRUE, resolved.getOAlreadyCompleted(), "a new run is not complete");
        assertEquals(TEMPLATE, resolved.getOEffectiveSqlText());
        assertEquals(schema(), resolved.getOEffectiveSchemaName());
        assertEquals(name(), resolved.getOEffectiveTableName());
        assertEquals("t", resolved.getOEffectiveAlias());
        assertEquals(1, resolved.getOEffectiveThreads());
        assertEquals(ChunkingStrategy.primary_key, resolved.getOChunkBy());
        assertArrayEquals(new String[]{"id"}, resolved.getOPrimaryKeyColumns());
        assertArrayEquals(new String[]{"bigint"}, resolved.getOKeyKinds());
        assertTrue(runStarted(resolved.getORunId()), "the new run should record started_at");
    }

    /**
     * An active run for the label is reused, and its stored sql_text, alias and
     * threads win over the differing inputs.
     */
    @Test
    void reusesAnActiveRunIgnoringDifferingInputs() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-resume");

        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1);
        ResolveMigrationRun second = resolve(label,
                "UPDATE <driving_table> SET payload = 'y' WHERE <chunking_clause>", "other", 99, 2);

        assertEquals(first.getORunId(), second.getORunId(), "the active run should be reused");
        assertNotEquals(Boolean.TRUE, second.getOAlreadyCompleted());
        assertEquals(TEMPLATE, second.getOEffectiveSqlText(), "the stored sql_text wins");
        assertEquals("t", second.getOEffectiveAlias(), "the stored alias wins");
        assertEquals(1, second.getOEffectiveThreads(), "the stored threads win");
    }

    /**
     * A completed run is reported as already complete.
     */
    @Test
    void reportsAnAlreadyCompletedRun() {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("resolve-complete");
        ResolveMigrationRun first = resolve(label, TEMPLATE, "t", 2, 1);

        dsl.update(MIGRATION_RUN)
                .set(MIGRATION_RUN.COMPLETED_AT, OffsetDateTime.now())
                .where(MIGRATION_RUN.RUN_ID.eq(first.getORunId()))
                .execute();

        ResolveMigrationRun again = resolve(label, TEMPLATE, "t", 2, 1);
        assertTrue(again.getOAlreadyCompleted(), "a completed run should be reported complete");
    }

    /**
     * A thread count above {@code max_worker_processes} is rejected with
     * {@code 22023} for a new run.
     */
    @Test
    void rejectsThreadsOverMaxWorkerProcesses() {
        int tooMany = dsl.fetchOne("SELECT current_setting('max_worker_processes')::int")
                .get(0, Integer.class) + 1;

        assertSqlState("22023", () -> resolve(uniqueLabel("resolve-threads"), TEMPLATE, "t", 2, tooMany));
    }

    private ResolveMigrationRun resolve(String label, String sqlText, String alias, int chunkSize, int threads) {
        return Routines.resolveMigrationRun(dsl.configuration(), sqlText, schema(), name(),
                label, chunkSize, threads, alias, ChunkingStrategy.primary_key);
    }

    private static String schema() {
        return TEST_BIGINT.getSchema().getName();
    }

    private static String name() {
        return TEST_BIGINT.getName();
    }
}
