package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationBoundaryRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.build_block_boundary_population_sql}: executing
 * the returned statement inserts contiguous block boundaries (one start every
 * chunk_size blocks plus a one-past-end terminal boundary) and inserts nothing
 * for a table with no blocks.
 */
class BuildBlockBoundaryPopulationSqlTest extends PostgresTestBase {

    private static String schema() {
        return TEST_BIGINT.getSchema().getName();
    }

    private static String name() {
        return TEST_BIGINT.getName();
    }

    /**
     * Clears the fixture table and the run metadata between tests.
     */
    @BeforeEach
    void reset() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * One start boundary per block plus the terminal boundary, contiguous 0..N,
     * with boundary i holding block i when the chunk is one block.
     */
    @Test
    void insertsContiguousBlockBoundaries() {
        seedBigint(1, 2, 3, 4, 5);
        long blockCount = blockCount();
        assertTrue(blockCount >= 1, "the seeded table should have at least one block");

        long runId = insertBlockRun();
        dsl.execute(Routines.buildBlockBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), runId, 1L));

        List<MigrationBoundaryRecord> boundaries = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();

        assertEquals(blockCount + 1, boundaries.size(), "one start per block plus the terminal");
        for (int i = 0; i < boundaries.size(); i++) {
            assertEquals(i, boundaries.get(i).getBoundaryNo().intValue());
            assertEquals(i, boundaries.get(i).getBoundaryId().getBigintValues()[0].intValue(),
                    "boundary i starts at block i when the chunk is one block");
        }
    }

    /**
     * A table with no blocks yields no boundaries.
     */
    @Test
    void insertsNothingForAnEmptyTable() {
        dsl.truncate(TEST_BIGINT).execute();
        assertEquals(0L, blockCount(), "a truncated table has no blocks");

        long runId = insertBlockRun();
        dsl.execute(Routines.buildBlockBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), runId, 1L));

        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)));
    }

    private long blockCount() {
        return dsl.fetchOne(
                        "SELECT pg_relation_size(?::regclass) / current_setting('block_size')::bigint",
                        schema() + "." + name())
                .get(0, Long.class);
    }

    private long insertBlockRun() {
        String filepath = Routines.relationFilepath(dsl.configuration(), schema(), name());
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.CHUNK_BY, MIGRATION_RUN.THREADS,
                        MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME, MIGRATION_RUN.DRIVING_TABLE_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_RELATION_FILEPATH)
                .values("block-boundary-test-" + UUID.randomUUID(), "SELECT 1", 1,
                        ChunkingStrategy.blocks, 1, schema(), name(), filepath)
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }
}
