package io.github.eyupmiduck.dmlutils;

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

/**
 * Verifies {@code dml_utils_lib.build_boundary_population_sql}: executing the
 * returned statement inserts contiguous primary-key chunk boundaries (one start
 * per chunk plus the terminal high-water boundary), inserts nothing for an empty
 * table, and rejects bad inputs.
 */
class BuildBoundaryPopulationSqlTest extends PostgresTestBase {

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
     * One start boundary per chunk plus the terminal boundary, contiguous 0..N,
     * with the terminal boundary holding the captured maximum key.
     */
    @Test
    void insertsAStartBoundaryPerChunkPlusTheTerminal() {
        seedBigint(1, 2, 3, 4, 5);
        long runId = insertRun();

        dsl.execute(Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, runId, 2L));

        List<MigrationBoundaryRecord> boundaries = dsl.selectFrom(MIGRATION_BOUNDARY)
                .where(MIGRATION_BOUNDARY.RUN_ID.eq(runId))
                .orderBy(MIGRATION_BOUNDARY.BOUNDARY_NO)
                .fetch();

        // Rows 1..5 at chunk size 2: starts at 1, 3 and 5 (boundary_no 0, 1, 2)
        // and a terminal high-water boundary at 5 (boundary_no 3).
        assertEquals(4, boundaries.size(), "three chunk starts plus the terminal");
        long[][] expected = {{0, 1}, {1, 3}, {2, 5}, {3, 5}};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], boundaries.get(i).getBoundaryNo().longValue(), "boundary_no " + i);
            assertEquals(expected[i][1],
                    boundaries.get(i).getBoundaryId().getBigintValues()[0].longValue(), "boundary_id " + i);
        }
    }

    /**
     * An empty table yields no boundaries.
     */
    @Test
    void insertsNothingForAnEmptyTable() {
        long runId = insertRun();

        dsl.execute(Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, runId, 2L));

        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY, MIGRATION_BOUNDARY.RUN_ID.eq(runId)));
    }

    /**
     * A NULL run id, a non-positive chunk size, mismatched columns/kinds and an
     * unsupported kind are all rejected with {@code 22023}.
     */
    @Test
    void rejectsBadInputs() {
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, null, 2L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"bigint"}, 1L, 0L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id", "id2"}, new String[]{"bigint"}, 1L, 2L));
        assertSqlState("22023", () -> Routines.buildBoundaryPopulationSql(dsl.configuration(),
                schema(), name(), new String[]{"id"}, new String[]{"int; DROP TABLE src"}, 1L, 2L));
    }

    private long insertRun() {
        return dsl.insertInto(MIGRATION_RUN)
                .columns(MIGRATION_RUN.LABEL, MIGRATION_RUN.SQL_TEXT, MIGRATION_RUN.CHUNK_SIZE,
                        MIGRATION_RUN.THREADS, MIGRATION_RUN.DRIVING_TABLE_SCHEMA_NAME,
                        MIGRATION_RUN.DRIVING_TABLE_NAME)
                .values("build-boundary-test-" + UUID.randomUUID(), "SELECT 1", 1, 1, schema(), name())
                .returningResult(MIGRATION_RUN.RUN_ID)
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }
}
