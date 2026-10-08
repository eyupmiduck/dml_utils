package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils.tables.records.ExplainMigrationChunksRecord;
import org.jooq.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationBoundary.MIGRATION_BOUNDARY;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestNumeric.TEST_NUMERIC;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestText.TEST_TEXT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestUuid.TEST_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.explain_migration_chunks}: it returns the EXPLAIN
 * plans of the boundary-population insert and of a representative non-final and
 * final chunk for the supplied template, and it is read-only (no run, no
 * boundary and no driving-table change).
 */
class ExplainMigrationChunksTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET payload = 'x' WHERE <chunking_clause>";

    private static final List<String> THREE_KINDS =
            List.of("boundary_population", "chunk_non_final", "chunk_final");

    private static List<String> planKinds(List<ExplainMigrationChunksRecord> plans) {
        return plans.stream().map(ExplainMigrationChunksRecord::getOPlanKind).toList();
    }

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

    /**
     * A bigint-keyed table yields exactly the three plan kinds in order.
     */
    @Test
    void returnsTheThreePlansInOrder() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key);

        assertEquals(THREE_KINDS, planKinds(plans));
    }

    /**
     * The block strategy plans the block boundary insert and two half-open ctid
     * ranges, without executing anything.
     */
    @Test
    void plansTheBlockStrategy() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t",
                ChunkingStrategy.blocks);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(0).getOSqlText().contains("INSERT INTO dml_utils_data.migration_boundary"),
                "the boundary plan is the boundary insert");
        assertTrue(plans.get(1).getOSqlText().contains("ctid")
                        && plans.get(1).getOSqlText().contains("'(0,0)'::tid")
                        && plans.get(1).getOSqlText().contains("'(10,0)'::tid"),
                "the non-final chunk is a half-open ctid range");
        assertTrue(plans.get(2).getOSqlText().contains("'(20,0)'::tid"),
                "the final chunk is a later half-open ctid range");
    }

    /**
     * A text key renders text literals in the chunk predicate and still plans
     * all three statements as a ModifyTable.
     */
    @Test
    void plansATextKeyWithTextLiterals() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_TEXT), name(TEST_TEXT), 10, "t", ChunkingStrategy.primary_key);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(1).getOSqlText().contains("'a'::text"),
                "the non-final text range starts at the synthetic a");
        assertTrue(plans.get(1).getOSqlText().contains("'b'::text"),
                "the non-final text range ends at the synthetic b");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A uuid key renders uuid literals in the chunk predicate and still plans
     * all three statements as a ModifyTable.
     */
    @Test
    void plansAUuidKeyWithUuidLiterals() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_UUID), name(TEST_UUID), 10, "t", ChunkingStrategy.primary_key);

        assertEquals(THREE_KINDS, planKinds(plans));
        assertTrue(plans.get(1).getOSqlText()
                        .contains("'00000000-0000-0000-0000-000000000000'::uuid"),
                "the non-final uuid range starts at the all-zero value");
        assertTrue(plans.get(1).getOSqlText()
                        .contains("'00000000-0000-0000-0000-000000000001'::uuid"),
                "the non-final uuid range ends at the all-zero-plus-one value");
        plans.forEach(plan -> assertEquals("ModifyTable", planNodeType(plan.getOPlan())));
    }

    /**
     * A composite key renders a row-value predicate with each position cast to
     * its own kind (bigint and text here).
     */
    @Test
    void plansACompositeKeyWithEachKindsLiteral() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), 10, "t", ChunkingStrategy.primary_key);

        assertEquals(THREE_KINDS, planKinds(plans));
        String nonFinal = plans.get(1).getOSqlText();
        assertTrue(nonFinal.contains("(t.a, t.b)"),
                "the predicate uses the composite row value");
        assertTrue(nonFinal.contains("'0'::bigint"), "the bigint position uses a bigint literal");
        assertTrue(nonFinal.contains("'a'::text"), "the text position uses a text literal");
        assertTrue(plans.get(2).getOSqlText().contains(" <= ("),
                "the final composite chunk is inclusive");
    }

    /**
     * Each row carries the statement that was explained and its plan: the
     * boundary insert and the chunk updates all plan as a ModifyTable, and the
     * non-final chunk uses a half-open range while the final one is inclusive.
     */
    @Test
    void plansTheBoundaryInsertAndBothChunkForms() {
        List<ExplainMigrationChunksRecord> plans = Routines.explainMigrationChunks(
                dsl.configuration(), TEMPLATE, schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key);

        ExplainMigrationChunksRecord boundary = plans.get(0);
        assertTrue(boundary.getOSqlText().contains("INSERT INTO dml_utils_data.migration_boundary"),
                "the boundary plan explains the boundary insert");
        assertEquals("ModifyTable", planNodeType(boundary.getOPlan()));

        ExplainMigrationChunksRecord nonFinal = plans.get(1);
        assertTrue(nonFinal.getOSqlText().contains(" < ("),
                "the non-final chunk is half-open");
        assertEquals("ModifyTable", planNodeType(nonFinal.getOPlan()));

        ExplainMigrationChunksRecord finalChunk = plans.get(2);
        assertTrue(finalChunk.getOSqlText().contains(" <= ("),
                "the final chunk is inclusive");
        assertEquals("ModifyTable", planNodeType(finalChunk.getOPlan()));
    }

    /**
     * The call is read-only: it creates no run or boundary and does not run the
     * template (the seeded payload is unchanged).
     */
    @Test
    void writesNothing() {
        dsl.insertInto(TEST_BIGINT, TEST_BIGINT.ID, TEST_BIGINT.PAYLOAD)
                .values(1L, "original")
                .execute();

        Routines.explainMigrationChunks(dsl.configuration(), TEMPLATE,
                schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key);

        assertEquals(0, dsl.fetchCount(MIGRATION_RUN), "no run is created");
        assertEquals(0, dsl.fetchCount(MIGRATION_BOUNDARY), "no boundary is written");
        assertEquals("original",
                dsl.select(TEST_BIGINT.PAYLOAD)
                        .from(TEST_BIGINT)
                        .where(TEST_BIGINT.ID.eq(1L))
                        .fetchOne(TEST_BIGINT.PAYLOAD),
                "the template must not run");
    }

    /**
     * A template without the required tokens is rejected with {@code 22023}
     * before any plan is produced.
     */
    @Test
    void rejectsABadTemplate() {
        assertSqlState("22023", () -> Routines.explainMigrationChunks(dsl.configuration(),
                "UPDATE nothing", schema(TEST_BIGINT), name(TEST_BIGINT), 10, "t", ChunkingStrategy.primary_key));
    }

    /**
     * A table whose primary key is an unsupported type is rejected with
     * {@code 22023}.
     */
    @Test
    void rejectsAnUnsupportedKeyType() {
        assertSqlState("22023", () -> Routines.explainMigrationChunks(dsl.configuration(),
                TEMPLATE, schema(TEST_NUMERIC), name(TEST_NUMERIC), 10, "t", ChunkingStrategy.primary_key));
    }

    /**
     * A missing driving table is rejected with {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> Routines.explainMigrationChunks(dsl.configuration(),
                TEMPLATE, "dml_utils_fixtures", "no_such_table", 10, "t", ChunkingStrategy.primary_key));
    }

}
