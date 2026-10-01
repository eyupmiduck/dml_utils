package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.assert_chunking_template} and
 * {@code dml_utils_lib.render_chunk_sql}: the template tokens must appear
 * exactly once, and substitution produces the expected driving-table reference
 * and half-open (or inclusive, for the final chunk) range predicate.
 */
class RenderChunkSqlTest extends PostgresTestBase {

    private static final String TEMPLATE =
            "UPDATE <driving_table> SET processed = true WHERE <chunking_clause>";

    /**
     * A valid template is accepted.
     */
    @Test
    void acceptsAValidTemplate() {
        Routines.assertChunkingTemplate(dsl.configuration(), TEMPLATE);
    }

    /**
     * A template missing {@code <driving_table>} is rejected with 22023.
     */
    @Test
    void rejectsMissingDrivingTableToken() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(), "UPDATE t SET x = true WHERE <chunking_clause>"));
    }

    /**
     * A template missing {@code <chunking_clause>} is rejected with 22023.
     */
    @Test
    void rejectsMissingChunkingClauseToken() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(), "UPDATE <driving_table> SET x = true"));
    }

    /**
     * A token appearing twice is rejected with 22023.
     */
    @Test
    void rejectsDuplicatedTokens() {
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(),
                "UPDATE <driving_table> SET x = true WHERE <chunking_clause> AND <chunking_clause>"));
        assertSqlState("22023", () -> Routines.assertChunkingTemplate(
                dsl.configuration(),
                "UPDATE <driving_table> JOIN <driving_table> ON true WHERE <chunking_clause>"));
    }

    /**
     * Substitution produces the qualified, aliased driving-table reference and
     * a non-final (half-open) range predicate.
     */
    @Test
    void rendersNonFinalChunk() {
        String rendered = render(false, 10L, 20L);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= 10 AND t.id < 20)",
                rendered);
    }

    /**
     * Substitution for the final chunk uses an inclusive upper bound.
     */
    @Test
    void rendersFinalChunk() {
        String rendered = render(true, 30L, 40L);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE (t.id >= 30 AND t.id <= 40)",
                rendered);
    }

    /**
     * The primary-key name is whatever the caller resolved from the catalog,
     * not hard-coded {@code id}, and it appears in both range bounds.
     */
    @Test
    void usesTheGivenPrimaryKeyName() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "s", "pk", 5L, 15L, false);

        assertEquals(
                "UPDATE public.src s SET processed = true"
                        + " WHERE (s.pk >= 5 AND s.pk < 15)",
                rendered);
    }

    /**
     * An explicit alias is used verbatim in the driving-table reference and the
     * range predicate.
     */
    @Test
    void usesTheGivenAlias() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "src_row", "id", 1L, 2L, false);

        assertEquals(
                "UPDATE public.src src_row SET processed = true"
                        + " WHERE (src_row.id >= 1 AND src_row.id < 2)",
                rendered);
    }

    /**
     * The schema, table, alias and primary-key name are quoted so unusual
     * identifiers (and a substituted value) cannot break the SQL or reintroduce
     * a token.
     */
    @Test
    void quotesIdentifiersAndCannotReintroduceTokens() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(),
                TEMPLATE,
                "my schema",
                "my table",
                "x\"y",
                "pk\"col",
                1L,
                2L,
                false);

        assertEquals(
                "UPDATE \"my schema\".\"my table\" \"x\"\"y\" SET processed = true"
                        + " WHERE (\"x\"\"y\".\"pk\"\"col\" >= 1"
                        + " AND \"x\"\"y\".\"pk\"\"col\" < 2)",
                rendered);
    }

    private String render(boolean isFinal, Long startId, Long endId) {
        return Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t", "id", startId, endId, isFinal);
    }
}
