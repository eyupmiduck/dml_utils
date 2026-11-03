package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.records.MigrationRunRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedMulti.TEST_PARTITIONED_MULTI;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestPartitionedRange.TEST_PARTITIONED_RANGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies partition-wise eligibility and safety checks: partition-wise ordered
 * keys resolve the parent index and every leaf's child index (including a
 * multi-level tree), an index that only exists on the parent is rejected before
 * a run row, and losing a leaf's UPDATE privilege during processing fails
 * closed while keeping earlier progress and allowing a retry.
 */
class PartitionWiseValidationTest extends PartitionTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>";

    /**
     * Empties the fixtures between tests.
     */
    @BeforeEach
    void reset() {
        dsl.truncate(TEST_PARTITIONED_RANGE).execute();
        dsl.truncate(TEST_PARTITIONED_MULTI).execute();
    }

    /**
     * A partition-wise ordered key on a range parent resolves the parent index
     * descriptor and processes every leaf.
     */
    @Test
    void orderedKeyRunProcessesEveryLeaf() {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("validation-ordered-range");

        run(PARTITIONED_RANGE, label, 2, ChunkingStrategy.ordered_key,
                "test_partitioned_range_ordered_key_idx");

        MigrationRunRecord run = run(label);
        assertEquals(ChunkingStrategy.ordered_key, run.getChunkBy());
        assertTrue(run.getPartitionWise());
        assertNotNull(run.getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    /**
     * A multi-level partitioned unique index validates every storage leaf's child
     * index through the recursive index tree, and the run completes.
     */
    @Test
    void multiLevelOrderedKeyRunProcessesEveryLeaf() {
        dsl.insertInto(TEST_PARTITIONED_MULTI,
                        TEST_PARTITIONED_MULTI.ID, TEST_PARTITIONED_MULTI.BUCKET,
                        TEST_PARTITIONED_MULTI.PAYLOAD)
                .values(0L, 0, "pending")
                .values(0L, 1, "pending")
                .values(100L, 0, "pending")
                .execute();
        String label = uniqueLabel("validation-ordered-multi");
        String index = "test_partitioned_multi_ordered_key_idx";

        assertArrayEquals(new String[]{"id", "bucket"},
                Routines.partitionedOrderedKeyColumns(dsl.configuration(),
                        PARTITION_SCHEMA, "test_partitioned_multi", index));
        assertArrayEquals(new String[]{"bigint", "bigint"},
                Routines.partitionedOrderedKeyKinds(dsl.configuration(),
                        PARTITION_SCHEMA, "test_partitioned_multi", index));

        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, PARTITION_SCHEMA,
                "test_partitioned_multi", label, 2, 1, "t",
                ChunkingStrategy.ordered_key, 1, 0, index, true);

        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_MULTI,
                TEST_PARTITIONED_MULTI.PAYLOAD.ne("done")));
    }

    /**
     * An index created with ON ONLY has no child indexes and is invalid, so the
     * parent-index validation fails before a run row is written.
     */
    @Test
    void rejectsAnIndexWithNoChildIndexes() throws Exception {
        insertRangeRows(0L);
        String index = "test_partitioned_range_only_idx";
        String label = uniqueLabel("validation-only-index");
        executeAsOwner("CREATE UNIQUE INDEX " + index + " ON ONLY "
                + PARTITION_SCHEMA + "." + PARTITIONED_RANGE + " (id)");
        try {
            assertSqlState("55000", () -> run(PARTITIONED_RANGE, label, 2,
                    ChunkingStrategy.ordered_key, index));
        } finally {
            executeAsOwner("DROP INDEX IF EXISTS " + PARTITION_SCHEMA + "." + index);
        }

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "no run row should be created");
    }

    /**
     * An index that belongs to another table is rejected with undefined_object.
     */
    @Test
    void rejectsAnIndexOnAnotherTable() {
        insertRangeRows(0L);
        String label = uniqueLabel("validation-wrong-index");

        assertSqlState("42704", () -> run(PARTITIONED_RANGE, label, 2,
                ChunkingStrategy.ordered_key, "test_partitioned_id_idx"));

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)));
    }

    /**
     * A leaf that loses UPDATE after calculation fails the chunk with 42501,
     * keeps earlier chunks committed, and completes after the grant is restored.
     */
    @Test
    void lostUpdatePrivilegeDuringProcessingFailsClosedAndResumes() throws Exception {
        insertRangeRows(0L, 1L, 2L, 100L, 101L, 200L, 201L, 202L);
        String label = uniqueLabel("validation-lost-update");
        long runId = populate(PARTITIONED_RANGE, label, 1, ChunkingStrategy.primary_key);
        Routines.calculateMigrationRunBoundaries(dsl.configuration(), runId);
        String highLeaf = PARTITION_SCHEMA + ".test_partitioned_range_high";

        executeAsOwner("REVOKE UPDATE ON " + highLeaf + " FROM "
                + TEST_USER + ", dml_utils_caller");
        try {
            assertSqlState("42501", () -> run(PARTITIONED_RANGE, label, 1,
                    ChunkingStrategy.primary_key, null));
        } finally {
            executeAsOwner("GRANT UPDATE ON " + highLeaf + " TO "
                    + TEST_USER + ", dml_utils_caller");
        }

        MigrationRunRecord failed = run(label);
        assertNull(failed.getCompletedAt(), "the run stays incomplete after a failed chunk");
        assertTrue(failed.getTotalChunksCompleted() > 0,
                "earlier chunks should stay committed");
        assertTrue(failed.getTotalChunksCompleted() < failed.getTotalChunks());

        run(PARTITIONED_RANGE, label, 1, ChunkingStrategy.primary_key, null);

        assertNotNull(run(label).getCompletedAt());
        assertEquals(0L, dsl.fetchCount(TEST_PARTITIONED_RANGE,
                TEST_PARTITIONED_RANGE.PAYLOAD.ne("done")));
    }

    private void insertRangeRows(Long... ids) {
        for (Long id : ids) {
            dsl.insertInto(TEST_PARTITIONED_RANGE,
                            TEST_PARTITIONED_RANGE.ID, TEST_PARTITIONED_RANGE.PAYLOAD)
                    .values(id, "pending")
                    .execute();
        }
    }

    private long populate(String table, String label, int chunkSize, ChunkingStrategy chunkBy) {
        return Routines.populateMigrationBoundaries(dsl.configuration(), PARTITION_SCHEMA,
                table, label, TEMPLATE, chunkSize, 1, chunkBy, "t", 0, null, true);
    }

    private void run(String table, String label, int chunkSize, ChunkingStrategy chunkBy,
                     String boundaryKeyIndex) {
        io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines.runMigrationChunks(dsl.configuration(), TEMPLATE, PARTITION_SCHEMA, table,
                label, chunkSize, 1, "t", chunkBy, 1, 0, boundaryKeyIndex, true);
    }

    private MigrationRunRecord run(String label) {
        return dsl.selectFrom(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne();
    }
}
