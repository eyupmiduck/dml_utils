package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlansRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies {@code dml_utils_lib.explain_chunk_plans}: it returns the JSON plans
 * for the three statements a chunked run generates — the boundary-population
 * insert, a non-final chunk and the final chunk — for both chunking strategies,
 * and rejects an invalid template.
 */
class ExplainChunkPlansTest extends PostgresTestBase {

    private static final String FIXTURE_SCHEMA = "dml_utils_fixtures";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    /**
     * A primary-key run yields the boundary-population, non-final and final plans.
     */
    @Test
    void returnsTheThreePrimaryKeyPlans() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"id"}, new String[]{"bigint"}, 1000L, ChunkingStrategy.primary_key);

        assertPlanKinds(plans);
    }

    /**
     * A block run yields the same three plan kinds.
     */
    @Test
    void returnsTheThreeBlockPlans() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"ctid"}, new String[]{"bigint"}, 10L, ChunkingStrategy.blocks);

        assertPlanKinds(plans);
    }

    /**
     * A template missing a token is rejected with {@code 22023} before any plan
     * is produced.
     */
    @Test
    void rejectsAnInvalidTemplate() {
        assertSqlState("22023", () -> Routines.explainChunkPlans(
                dsl.configuration(), "UPDATE t SET x = 1", FIXTURE_SCHEMA, "test_bigint", "t",
                new String[]{"id"}, new String[]{"bigint"}, 1000L, ChunkingStrategy.primary_key));
    }

    private static void assertPlanKinds(List<ExplainChunkPlansRecord> plans) {
        assertEquals(3, plans.size(), "one row per generated statement");
        assertEquals("boundary_population", plans.get(0).getOPlanKind());
        assertEquals("chunk_non_final", plans.get(1).getOPlanKind());
        assertEquals("chunk_final", plans.get(2).getOPlanKind());
        for (ExplainChunkPlansRecord plan : plans) {
            assertNotNull(plan.getOSqlText(), "each row carries the SQL it explained");
            assertNotNull(plan.getOPlan(), "each row carries a JSON plan");
        }
    }
}
