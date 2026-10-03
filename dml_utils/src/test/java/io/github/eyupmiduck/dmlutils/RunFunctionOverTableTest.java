package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestCompositeMixed.TEST_COMPOSITE_MIXED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils.run_function_over_table}: it validates the
 * user-supplied function's signature against the driving table's primary key,
 * builds a per-row template, and runs it chunk by chunk via the base engine,
 * including resume and the {@code set_migration_run_function} mutator.
 */
class RunFunctionOverTableTest extends PostgresTestBase {

    private static final String FN_SCHEMA = PUBLIC_SCHEMA;

    private static String TEMPLATE(String functionName) {
        return "SELECT public." + functionName + "(t.a, t.b) FROM <driving_table>"
                + " WHERE <chunking_clause>";
    }

    private static String schema(Table<?> table) {
        return table.getSchema().getName();
    }

    private static String name(Table<?> table) {
        return table.getName();
    }

    /**
     * Clears the fixture tables each test drives.
     */
    @BeforeEach
    void resetFixtures() {
        for (Table<?> table : List.of(TEST_BIGINT, TEST_COMPOSITE_MIXED)) {
            dsl.truncate(table).execute();
        }
    }

    /**
     * Drops the test functions so a rerun of the class starts clean.
     */
    @AfterEach
    void dropFunctions() {
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_mixed(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_mixed_2(bigint, text)");
        dsl.execute("DROP FUNCTION IF EXISTS public.mark_bigint(bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.wrong_order(text, bigint)");
        dsl.execute("DROP FUNCTION IF EXISTS public.bad_return(bigint, text)");
    }

    /**
     * A void function whose arguments match the composite primary key is applied
     * to every row, one chunk at a time, and the run completes.
     */
    @Test
    void appliesAMatchingFunctionOverEveryRow() {
        createMixed();
        createMarkMixed("done");
        String label = uniqueLabel("mixed");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, label);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "the function should update every row");
        assertTrue(runCompleted(label), "the run should be marked complete");
    }

    /**
     * An omitted label is derived deterministically from the table and function,
     * so a second identical call resumes the same run rather than creating a new
     * one.
     */
    @Test
    void derivesTheLabelAndResumesOnASecondCall() {
        createMixed();
        createMarkMixed("done");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 2, 1, null);
        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed", 99, 4, null);

        String derivedLabel = "function:" + schema(TEST_COMPOSITE_MIXED) + "."
                + name(TEST_COMPOSITE_MIXED) + ":" + FN_SCHEMA + ".mark_mixed";
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(derivedLabel)),
                "both calls share one derived run");
        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "done"),
                "every row should still be updated exactly once");
    }

    /**
     * {@code set_migration_run_function} swaps the function on an unfinished run,
     * changing the stored {@code sql_text} the next call renders.
     */
    @Test
    void setMigrationRunFunctionChangesTheStoredSql() {
        createMixed();
        createMarkMixed("first");
        createMarkMixed2("second");
        String label = uniqueLabel("setfn");

        // Create the run without processing it, then swap in the second function.
        io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines.populateMigrationBoundaries(
                dsl.configuration(), schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED),
                label, TEMPLATE("mark_mixed"), 2, 1);
        Routines.setMigrationRunFunction(dsl.configuration(), label, FN_SCHEMA, "mark_mixed_2");

        assertTrue(storedSqlText(label).contains("mark_mixed_2"),
                "the stored sql_text should now reference the new function");

        Routines.runFunctionOverTable(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed_2", 2, 1, label);

        assertEquals(3, payloadCount(TEST_COMPOSITE_MIXED, "second"),
                "the run should apply the swapped-in function");
    }

    /**
     * {@code set_migration_run_function} raises {@code P0002} when there is no
     * unfinished run for the label.
     */
    @Test
    void setMigrationRunFunctionRaisesWhenNoUnfinishedRun() {
        createMarkMixed("done");
        assertSqlState("P0002", () -> Routines.setMigrationRunFunction(
                dsl.configuration(), "no-such-label", FN_SCHEMA, "mark_mixed"));
    }

    /**
     * A function whose argument types do not match the primary key, in key
     * order, is rejected up front with {@code 22023} and writes nothing.
     */
    @Test
    void rejectsAFunctionWithMismatchedArguments() {
        dsl.execute("CREATE FUNCTION public.wrong_order(text, bigint) RETURNS void"
                + " LANGUAGE sql AS $$ SELECT NULL::void $$");

        int runsBefore = dsl.fetchCount(MIGRATION_RUN);
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "wrong_order", 2, 1, null));
        assertEquals(runsBefore, dsl.fetchCount(MIGRATION_RUN),
                "a rejected function must not create a run");
    }

    /**
     * A function that does not return void is rejected with {@code 22023}.
     */
    @Test
    void rejectsAFunctionThatDoesNotReturnVoid() {
        dsl.execute("CREATE FUNCTION public.bad_return(bigint, text) RETURNS integer"
                + " LANGUAGE sql AS $$ SELECT 1 $$");

        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "bad_return", 2, 1, null));
    }

    /**
     * A missing function is rejected with {@code 22023}.
     */
    @Test
    void rejectsAMissingFunction() {
        assertSqlState("22023", () -> Routines.runFunctionOverTable(dsl.configuration(),
                schema(TEST_COMPOSITE_MIXED), name(TEST_COMPOSITE_MIXED), FN_SCHEMA,
                "no_such_function", 2, 1, null));
    }

    /**
     * The template the wrapper builds calls the function once per row with the
     * primary-key columns as arguments, in key order.
     */
    @Test
    void buildsThePerRowFunctionTemplate() {
        createMarkMixed("done");

        String template = io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines
                .buildFunctionChunkTemplate(dsl.configuration(), schema(TEST_COMPOSITE_MIXED),
                        name(TEST_COMPOSITE_MIXED), FN_SCHEMA, "mark_mixed");

        assertEquals("SELECT public.mark_mixed(t.a, t.b) FROM <driving_table>"
                + " WHERE <chunking_clause>", template);
    }

    private void createMixed() {
        dsl.insertInto(TEST_COMPOSITE_MIXED, TEST_COMPOSITE_MIXED.A, TEST_COMPOSITE_MIXED.B)
                .values(1L, "x")
                .values(1L, "y")
                .values(2L, "x")
                .execute();
    }

    private void createMarkMixed(String value) {
        createFunction("mark_mixed", value);
    }

    private void createMarkMixed2(String value) {
        createFunction("mark_mixed_2", value);
    }

    private void createFunction(String functionName, String value) {
        dsl.execute("CREATE OR REPLACE FUNCTION public." + functionName
                + "(p_a bigint, p_b text) RETURNS void LANGUAGE sql AS $$"
                + " UPDATE " + schema(TEST_COMPOSITE_MIXED) + "." + name(TEST_COMPOSITE_MIXED)
                + " SET payload = '" + value + "' WHERE a = p_a AND b = p_b $$");
    }

    private String uniqueLabel(String suffix) {
        return "function-test-" + suffix + "-" + System.nanoTime();
    }

    private boolean runCompleted(String label) {
        return Boolean.TRUE.equals(dsl.select(MIGRATION_RUN.COMPLETED_AT.isNotNull())
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.COMPLETED_AT.isNotNull()));
    }

    private String storedSqlText(String label) {
        return dsl.select(MIGRATION_RUN.SQL_TEXT)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.SQL_TEXT);
    }

    private int payloadCount(Table<?> table, String value) {
        return dsl.selectCount()
                .from(table)
                .where(field("payload", String.class).eq(value))
                .fetchOne(0, Integer.class);
    }
}
