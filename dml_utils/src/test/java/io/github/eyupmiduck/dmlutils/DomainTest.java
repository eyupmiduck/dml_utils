package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the Liquibase changelog creates the {@code dml_utils_data.non_null_text}
 * domain with the expected constraints.
 */
class DomainTest extends PostgresTestBase {

    /**
     * The text domain accepts a non-null value.
     */
    @Test
    void nonNullTextAcceptsText() {
        assertEquals("hello", evaluate("'hello'::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain rejects null with a check-constraint violation.
     */
    @Test
    void nonNullTextRejectsNull() {
        assertDomainViolation(() -> evaluate("NULL::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain rejects an empty string with a check-constraint
     * violation.
     */
    @Test
    void nonNullTextRejectsEmptyString() {
        assertDomainViolation(() -> evaluate("''::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain rejects a whitespace-only string with a
     * check-constraint violation.
     */
    @Test
    void nonNullTextRejectsBlankString() {
        assertDomainViolation(() -> evaluate("'   '::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain rejects a string made up only of non-space whitespace
     * (tabs, newlines, carriage returns) with a check-constraint violation.
     */
    @Test
    void nonNullTextRejectsNonSpaceWhitespace() {
        assertDomainViolation(() -> evaluate("E'\\t\\n\\r'::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain does not treat the literal letter 'v' as whitespace: a
     * value made up of v's is accepted.
     *
     * <p>Regression test for the {@code E'\v'} escape, which PostgreSQL reads
     * as the letter 'v' rather than vertical tab.
     */
    @Test
    void nonNullTextAcceptsLetterV() {
        assertEquals("vvv", evaluate("'vvv'::dml_utils_data.non_null_text", String.class));
    }

    /**
     * The text domain rejects a string made up only of vertical tabs, the
     * character the buggy {@code E'\v'} literal was meant to represent.
     */
    @Test
    void nonNullTextRejectsVerticalTab() {
        assertDomainViolation(() -> evaluate("E'\\013'::dml_utils_data.non_null_text", String.class));
    }
}
