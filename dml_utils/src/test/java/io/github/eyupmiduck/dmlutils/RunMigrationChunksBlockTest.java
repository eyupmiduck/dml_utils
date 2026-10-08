package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestOther.TEST_OTHER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code run_migration_chunks} with the block (ctid) strategy: it
 * processes every row of a read-only driving table exactly once, records the
 * strategy and the physical filepath, and leaves the source table unchanged.
 */
class RunMigrationChunksBlockTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "INSERT INTO dml_utils_fixtures.test_other (id, payload) "
                    + "SELECT t.id, t.payload FROM <driving_table> WHERE <chunking_clause>";

    /**
     * Clears the source, target and run metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
        dsl.truncate(TEST_OTHER).execute();
    }

    /**
     * A block-chunked run copies every source row into the target exactly once
     * (a duplicate would violate the target's primary key) and records the
     * strategy and filepath.
     */
    @Test
    void processesEveryRowExactlyOnce() {
        seedBigint(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        String label = uniqueLabel("block");

        Routines.runMigrationChunks(dsl.configuration(), TEMPLATE,
                TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(),
                label, 1, 1, "t", ChunkingStrategy.blocks);

        assertEquals(10, dsl.fetchCount(TEST_OTHER), "every source row is inserted exactly once");
        assertEquals(10, dsl.fetchCount(TEST_BIGINT), "the source table is unchanged");
        assertTrue(runCompleted(label), "the run should be complete");

        assertEquals(ChunkingStrategy.blocks,
                dsl.select(MIGRATION_RUN.CHUNK_BY)
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.LABEL.eq(label))
                        .fetchOne(MIGRATION_RUN.CHUNK_BY),
                "the run records the block strategy");
        assertNotNull(dsl.select(MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH)
                        .from(MIGRATION_RUN)
                        .where(MIGRATION_RUN.LABEL.eq(label))
                        .fetchOne(MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH),
                "a block run records the driving table filepath");
    }
}
