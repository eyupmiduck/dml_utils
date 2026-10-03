package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@code dml_utils_lib.assert_chunking_template} and
 * {@code dml_utils_lib.render_chunk_sql}: the template tokens must appear
 * exactly once, and substitution produces the expected driving-table reference
 * and half-open (or inclusive, for the final chunk) row-value range predicate
 * with an explicitly cast key literal per column.
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
     * a non-final (half-open) range predicate with an explicit bigint cast. A
     * one-column key renders as a one-element row.
     */
    @Test
    void rendersNonFinalChunk() {
        String rendered = render(false, "bigint", "10", "20");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.id) >= ('10'::bigint) AND (t.id) < ('20'::bigint))",
                rendered);
    }

    /**
     * Substitution for the final chunk uses an inclusive upper bound.
     */
    @Test
    void rendersFinalChunk() {
        String rendered = render(true, "bigint", "30", "40");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.id) >= ('30'::bigint) AND (t.id) <= ('40'::bigint))",
                rendered);
    }

    /**
     * A text key is rendered as a quoted literal cast to text.
     */
    @Test
    void rendersTextKeyWithAnExplicitCast() {
        String rendered = render(false, "text", "abc", "def");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.id) >= ('abc'::text) AND (t.id) < ('def'::text))",
                rendered);
    }

    /**
     * A uuid key is rendered as a quoted literal cast to uuid.
     */
    @Test
    void rendersUuidKeyWithAnExplicitCast() {
        String start = new UUID(0x1122334455667788L, 0x99aabbccddeeff00L).toString();
        String end = new UUID(0x1122334455667788L, 0x99aabbccddeeff01L).toString();

        String rendered = render(false, "uuid", start, end);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.id) >= ('11223344-5566-7788-99aa-bbccddeeff00'::uuid)"
                        + " AND (t.id) < ('11223344-5566-7788-99aa-bbccddeeff01'::uuid))",
                rendered);
    }

    /**
     * A composite key renders as a row-value comparison with one explicitly cast
     * literal per column, in key order.
     */
    @Test
    void rendersCompositeKeyAsARowValueComparison() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.a, t.b) >= ('1'::bigint, 'x'::text)"
                        + " AND (t.a, t.b) < ('2'::bigint, 'y'::text))",
                rendered);
    }

    /**
     * A three-column, mixed-kind key renders one explicitly cast literal per
     * column, in key order.
     */
    @Test
    void rendersAThreeColumnCompositeKeyAsARowValueComparison() {
        String startUuid = new UUID(0x1122334455667788L, 0x99aabbccddeeff00L).toString();
        String endUuid = new UUID(0x1122334455667788L, 0x99aabbccddeeff01L).toString();

        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b", "c"}, new String[]{"bigint", "text", "uuid"},
                new String[]{"1", "x", startUuid}, new String[]{"2", "y", endUuid}, false);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.a, t.b, t.c) >= ('1'::bigint, 'x'::text,"
                        + " '11223344-5566-7788-99aa-bbccddeeff00'::uuid)"
                        + " AND (t.a, t.b, t.c) < ('2'::bigint, 'y'::text,"
                        + " '11223344-5566-7788-99aa-bbccddeeff01'::uuid))",
                rendered);
    }

    /**
     * A composite key's final chunk uses an inclusive upper bound, so the
     * captured maximum row is included.
     */
    @Test
    void rendersCompositeFinalChunk() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, true);

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.a, t.b) >= ('1'::bigint, 'x'::text)"
                        + " AND (t.a, t.b) <= ('2'::bigint, 'y'::text))",
                rendered);
    }

    /**
     * A quote inside a text key is escaped by the literal, so it cannot break
     * out of the generated SQL.
     */
    @Test
    void escapesQuotesInTextKeys() {
        String rendered = render(false, "text", "O'Brien", "O'Dad");

        assertEquals(
                "UPDATE public.src t SET processed = true"
                        + " WHERE ((t.id) >= ('O''Brien'::text) AND (t.id) < ('O''Dad'::text))",
                rendered);
    }

    /**
     * A key kind outside the whitelist is rejected, since the kind is
     * interpolated into the generated SQL.
     */
    @Test
    void rejectsAnUnknownKeyKind() {
        assertSqlState("22023", () -> render(false, "int; DROP TABLE src", "1", "2"));
    }

    /**
     * A NULL boundary value is rejected: {@code format('%L', NULL)} would render
     * an unquoted NULL, so the predicate would silently match no rows while the
     * chunk is still marked complete.
     */
    @Test
    void rejectsNullBoundaryValues() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint", "text"},
                new String[]{"1", null}, new String[]{"2", "y"}, false));

        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{null}, false));
    }

    /**
     * A NULL primary-key column name is rejected: {@code %I} on NULL would
     * render a degenerate identifier that only fails when a worker parses the
     * chunk SQL.
     */
    @Test
    void rejectsANullPrimaryKeyColumnName() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{null}, new String[]{"bigint"},
                new String[]{"1"}, new String[]{"2"}, false));
    }

    /**
     * Arrays of differing length (columns, kinds or values) are rejected, since
     * they cannot be zipped into one row-value comparison.
     */
    @Test
    void rejectsMismatchedArrayLengths() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a", "b"}, new String[]{"bigint"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false));

        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"a"}, new String[]{"bigint"},
                new String[]{"1", "x"}, new String[]{"2", "y"}, false));
    }

    /**
     * Empty arrays are rejected: {@code array_length} of an empty array is NULL,
     * so a length comparison alone would let them through and render an empty,
     * malformed predicate.
     */
    @Test
    void rejectsEmptyArrays() {
        assertSqlState("22023", () -> Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{}, new String[]{},
                new String[]{}, new String[]{}, false));
    }

    /**
     * The primary-key names are whatever the caller resolved from the catalog,
     * not hard-coded {@code id}, and they appear in both range bounds.
     */
    @Test
    void usesTheGivenPrimaryKeyName() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "s",
                new String[]{"pk"}, new String[]{"bigint"}, new String[]{"5"}, new String[]{"15"}, false);

        assertEquals(
                "UPDATE public.src s SET processed = true"
                        + " WHERE ((s.pk) >= ('5'::bigint) AND (s.pk) < ('15'::bigint))",
                rendered);
    }

    /**
     * An explicit alias is used verbatim in the driving-table reference and the
     * range predicate.
     */
    @Test
    void usesTheGivenAlias() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "src_row",
                new String[]{"id"}, new String[]{"bigint"}, new String[]{"1"}, new String[]{"2"}, false);

        assertEquals(
                "UPDATE public.src src_row SET processed = true"
                        + " WHERE ((src_row.id) >= ('1'::bigint) AND (src_row.id) < ('2'::bigint))",
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
                new String[]{"pk\"col"},
                new String[]{"bigint"},
                new String[]{"1"},
                new String[]{"2"},
                false);

        assertEquals(
                "UPDATE \"my schema\".\"my table\" \"x\"\"y\" SET processed = true"
                        + " WHERE ((\"x\"\"y\".\"pk\"\"col\") >= ('1'::bigint)"
                        + " AND (\"x\"\"y\".\"pk\"\"col\") < ('2'::bigint))",
                rendered);
    }

    /**
     * An identifier that literally contains a template token does not get
     * rewritten: the substituted values are inserted after both tokens are
     * replaced, so neither substitution can re-match the other's value.
     */
    @Test
    void doesNotRewriteATokenInsideASubstitutedIdentifier() {
        String rendered = Routines.renderChunkSql(
                dsl.configuration(),
                TEMPLATE,
                "public",
                "<chunking_clause>",
                "t",
                new String[]{"id"},
                new String[]{"bigint"},
                new String[]{"1"},
                new String[]{"2"},
                false);

        assertEquals(
                "UPDATE public.\"<chunking_clause>\" t SET processed = true"
                        + " WHERE ((t.id) >= ('1'::bigint) AND (t.id) < ('2'::bigint))",
                rendered);
    }

    private String render(boolean isFinal, String keyKind, String startValue, String endValue) {
        return Routines.renderChunkSql(
                dsl.configuration(), TEMPLATE, "public", "src", "t",
                new String[]{"id"}, new String[]{keyKind},
                new String[]{startValue}, new String[]{endValue}, isFinal);
    }
}
