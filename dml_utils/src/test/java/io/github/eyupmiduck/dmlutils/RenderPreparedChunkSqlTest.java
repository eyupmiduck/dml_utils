package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.routines.PrepareChunkSqlRender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.render_prepared_chunk_sql}: it renders one chunk
 * from the context returned by {@code prepare_chunk_sql_render}, so a caller can
 * prepare once per run and render every chunk without repeating the template
 * validation and quoting.
 */
class RenderPreparedChunkSqlTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET processed = true WHERE <chunking_clause>";

    /**
     * Two different chunks rendered from the same prepared context produce the
     * expected box disjunctions, so one context serves a whole run.
     */
    @Test
    void rendersDifferentChunksFromOnePreparedContext() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});
        String[] kinds = {"bigint", "text"};

        String nonFinal = renderPrepared(prepared, kinds,
                new String[]{"1", "x"}, new String[]{"2", "y"}, false);
        String finalChunk = renderPrepared(prepared, kinds,
                new String[]{"2", "y"}, new String[]{"3", "z"}, true);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '1'::bigint AND t.b >= 'x'::text"
                        + " OR t.a > '1'::bigint AND t.a < '2'::bigint"
                        + " OR t.a = '2'::bigint AND t.b < 'y'::text)",
                nonFinal);
        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.a = '2'::bigint AND t.b >= 'y'::text"
                        + " OR t.a > '2'::bigint AND t.a < '3'::bigint"
                        + " OR t.a = '3'::bigint AND t.b <= 'z'::text)",
                finalChunk);
    }

    /**
     * A prepared template without the chunking-clause placeholder is rejected,
     * since the clause would never be substituted and the chunk would render with
     * no predicate.
     */
    @Test
    void rejectsATemplateWithoutTheClausePlaceholder() {
        assertSqlState("22023", () -> Routines.renderPreparedChunkSql(
                dsl.configuration(), "UPDATE public.src t SET processed = true",
                new String[]{"t.a"}, new String[]{"bigint"}, new String[]{null},
                new String[]{"1"}, new String[]{"2"}, false));
    }

    /**
     * A reversed range is rejected: the predicate would match no rows while the
     * worker still marks the boundary complete.
     */
    @Test
    void rejectsAReversedRange() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"});

        assertSqlState("22023", () -> renderPrepared(prepared, new String[]{"bigint"},
                new String[]{"2"}, new String[]{"1"}, false));
    }

    /**
     * Boundary arrays that do not match the prepared columns are rejected.
     */
    @Test
    void rejectsMismatchedBoundaryArrays() {
        PrepareChunkSqlRender prepared = Routines.prepareChunkSqlRender(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"});

        assertSqlState("22023", () -> renderPrepared(prepared, new String[]{"bigint", "text"},
                new String[]{"1"}, new String[]{"2", "y"}, false));
    }

    private String renderPrepared(PrepareChunkSqlRender prepared, String[] kinds,
                                  String[] start, String[] end, boolean isFinal) {
        return Routines.renderPreparedChunkSql(
                dsl.configuration(),
                prepared.getOTemplate(),
                prepared.getOColumnNames(),
                kinds,
                prepared.getOKeyCollations(),
                start, end, isFinal);
    }
}
