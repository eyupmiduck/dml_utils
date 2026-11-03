package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.PrepareChunkSqlRender;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlanRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.LeafPartitionsRecord;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.tables.records.ExplainChunkPlansRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies per-leaf rendering: a chunk of a partition-wise run targets the leaf
 * relation (never the parent), the same prepared context renders different
 * leaves, unusual leaf names are quoted, and the explain path plans the leaf.
 */
class RenderLeafChunkSqlTest extends PostgresTestBase {

    private static final String SCHEMA = "dml_utils_fixtures";
    private static final String LEAF = "test_partitioned_range_low";
    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";
    private static final String[] KEY_COLUMNS = {"id"};
    private static final String[] KEY_KINDS = {"bigint"};

    /**
     * A key chunk renders against the leaf relation, not the parent.
     */
    @Test
    void rendersAKeyChunkAgainstTheLeaf() {
        String sql = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, SCHEMA, LEAF, "t",
                KEY_COLUMNS, KEY_KINDS, new String[]{"1"}, new String[]{"2"}, false);

        assertTrue(sql.startsWith("UPDATE " + SCHEMA + "." + LEAF + " t "),
                "the chunk should update the leaf: " + sql);
        assertFalse(sql.contains(SCHEMA + ".test_partitioned_range t"),
                "the parent must not be the driving table");
        assertTrue(sql.contains("t.id >= '1'::bigint"), "the range predicate should be rendered");
    }

    /**
     * A block chunk renders a ctid range against the leaf heap.
     */
    @Test
    void rendersABlockChunkAgainstTheLeaf() {
        String sql = Routines.renderBlockChunkSql(
                dsl.configuration(), TEMPLATE, SCHEMA, LEAF, "t", 0L, 1L);

        assertTrue(sql.startsWith("UPDATE " + SCHEMA + "." + LEAF + " t "),
                "the block chunk should update the leaf: " + sql);
        assertTrue(sql.contains("ctid"), "the block predicate should be rendered");
    }

    /**
     * One prepared context (built from the parent) renders every leaf, which is
     * what the runner does per boundary.
     */
    @Test
    void rendersDifferentLeavesFromOnePreparedContext() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, SCHEMA, "test_partitioned_range", "t",
                KEY_COLUMNS, KEY_KINDS);

        String low = renderLeaf(prepared, "test_partitioned_range_low");
        String high = renderLeaf(prepared, "test_partitioned_range_high");

        assertTrue(low.startsWith("UPDATE " + SCHEMA + ".test_partitioned_range_low t "), low);
        assertTrue(high.startsWith("UPDATE " + SCHEMA + ".test_partitioned_range_high t "), high);
    }

    /**
     * A leaf name containing a space and a double quote is enumerated from the
     * catalog and quoted with %I, so it cannot break out of the identifier. The
     * explain call parses the rendered identifier, proving it resolves to the real
     * fixture relation.
     */
    @Test
    void quotesAnUnusualLeafName() {
        List<LeafPartitionsRecord> leaves = Routines.leafPartitions(
                dsl.configuration(), SCHEMA, "test_partitioned_quoted",
                ChunkingStrategy.primary_key);
        assertEquals(1, leaves.size());
        String leaf = leaves.getFirst().getLeafName();
        assertEquals("test partitioned \"odd\"", leaf,
                "the fixture leaf name should reach the renderer unchanged");

        String sql = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, SCHEMA, leaf, "t",
                KEY_COLUMNS, KEY_KINDS, new String[]{"1"}, new String[]{"2"}, false);

        assertTrue(sql.startsWith(
                        "UPDATE " + SCHEMA + ".\"test partitioned \"\"odd\"\"\" t "),
                "the odd leaf name should be quoted: " + sql);

        ExplainChunkPlanRecord plan = Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, SCHEMA, leaf, "t",
                KEY_COLUMNS, KEY_KINDS, new String[]{"1"}, new String[]{"2"}, false).getFirst();
        assertEquals("ModifyTable", planNodeType(plan.getOPlan()));
        assertTrue(plan.getOPlan().data().contains("test partitioned"),
                "the plan should name the odd leaf: " + plan.getOPlan().data());
    }

    /**
     * The explain path plans a leaf chunk against that leaf, and the leaf appears
     * in the plan.
     */
    @Test
    void explainsALeafKeyChunk() {
        ExplainChunkPlanRecord plan = Routines.explainChunkPlan(
                dsl.configuration(), TEMPLATE, SCHEMA, LEAF, "t",
                KEY_COLUMNS, KEY_KINDS, new String[]{"1"}, new String[]{"2"}, false).getFirst();

        assertTrue(plan.getOSqlText().contains(SCHEMA + "." + LEAF + " t"),
                "the explained SQL should target the leaf");
        assertEquals("ModifyTable", planNodeType(plan.getOPlan()));
        assertTrue(plan.getOPlan().data().contains(LEAF),
                "the plan should name the leaf: " + plan.getOPlan().data());
    }

    /**
     * The synthetic block explain renders and plans the leaf's ctid ranges.
     */
    @Test
    void explainsLeafBlockChunks() {
        List<ExplainChunkPlansRecord> plans = Routines.explainChunkPlans(
                dsl.configuration(), TEMPLATE, SCHEMA, LEAF, "t",
                new String[]{"ctid"}, KEY_KINDS, 10L, ChunkingStrategy.blocks);

        assertEquals(3, plans.size());
        for (ExplainChunkPlansRecord plan : plans) {
            assertTrue(plan.getOSqlText().contains(SCHEMA + "." + LEAF),
                    "every plan should target the leaf: " + plan.getOSqlText());
        }
    }

    private String renderLeaf(PrepareChunkSqlRender prepared, String leaf) {
        return Routines.renderPreparedChunkSql(
                dsl.configuration(), prepared.getOTemplate(), SCHEMA, leaf, "t",
                prepared.getOColumnNames(), KEY_KINDS, prepared.getOKeyCollations(),
                new String[]{"1"}, new String[]{"2"}, false);
    }
}
