package io.github.eyupmiduck.dmlutils;

import org.jooq.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the Liquibase changelog creates the {@code dml_utils} domains
 * (scalar and array) with the expected constraints.
 */
class DomainTest extends PostgresTestBase {

    /**
     * The integer domain accepts zero and positive values.
     */
    @Test
    void nonNegativeIntegerAcceptsZeroAndPositiveValues() {
        assertEquals(0, evaluate("0::dml_utils.non_negative_integer", Integer.class));
        assertEquals(7, evaluate("7::dml_utils.non_negative_integer", Integer.class));
    }

    /**
     * The integer domain rejects negative values with a check-constraint
     * violation.
     */
    @Test
    void nonNegativeIntegerRejectsNegativeValues() {
        assertDomainViolation(() -> evaluate("(-1)::dml_utils.non_negative_integer", Integer.class));
    }

    /**
     * The integer domain rejects null with a check-constraint violation.
     */
    @Test
    void nonNegativeIntegerRejectsNull() {
        assertDomainViolation(() -> evaluate("NULL::dml_utils.non_negative_integer", Integer.class));
    }

    /**
     * The text domain accepts a non-null value.
     */
    @Test
    void nonNullTextAcceptsText() {
        assertEquals("hello", evaluate("'hello'::dml_utils.non_null_text", String.class));
    }

    /**
     * The text domain rejects null with a check-constraint violation.
     */
    @Test
    void nonNullTextRejectsNull() {
        assertDomainViolation(() -> evaluate("NULL::dml_utils.non_null_text", String.class));
    }

    /**
     * The text domain rejects an empty string with a check-constraint
     * violation.
     */
    @Test
    void nonNullTextRejectsEmptyString() {
        assertDomainViolation(() -> evaluate("''::dml_utils.non_null_text", String.class));
    }

    /**
     * The text domain rejects a whitespace-only string with a
     * check-constraint violation.
     */
    @Test
    void nonNullTextRejectsBlankString() {
        assertDomainViolation(() -> evaluate("'   '::dml_utils.non_null_text", String.class));
    }

    /**
     * The text domain rejects a string made up only of non-space whitespace
     * (tabs, newlines, carriage returns) with a check-constraint violation.
     */
    @Test
    void nonNullTextRejectsNonSpaceWhitespace() {
        assertDomainViolation(() -> evaluate("E'\\t\\n\\r'::dml_utils.non_null_text", String.class));
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
        assertEquals("vvv", evaluate("'vvv'::dml_utils.non_null_text", String.class));
    }

    /**
     * The text domain rejects a string made up only of vertical tabs, the
     * character the buggy {@code E'\v'} literal was meant to represent.
     */
    @Test
    void nonNullTextRejectsVerticalTab() {
        assertDomainViolation(() -> evaluate("E'\\013'::dml_utils.non_null_text", String.class));
    }

    /**
     * The boolean domain accepts true and false.
     */
    @Test
    void nonNullBooleanAcceptsTrueAndFalse() {
        assertEquals(true, evaluate("true::dml_utils.non_null_boolean", Boolean.class));
        assertEquals(false, evaluate("false::dml_utils.non_null_boolean", Boolean.class));
    }

    /**
     * The boolean domain rejects null with a check-constraint violation.
     */
    @Test
    void nonNullBooleanRejectsNull() {
        assertDomainViolation(() -> evaluate("NULL::dml_utils.non_null_boolean", Boolean.class));
    }

    /**
     * The non-empty text-array domain accepts an array with elements, including
     * null elements.
     */
    @Test
    void nonEmptyTextArrayAcceptsElements() {
        assertEquals(2, evaluate(
                "pg_catalog.cardinality(ARRAY['a', 'b']::dml_utils.non_empty_text_array)", Integer.class));
        assertEquals(2, evaluate(
                "pg_catalog.cardinality(ARRAY['a', NULL]::dml_utils.non_empty_text_array)", Integer.class));
    }

    /**
     * The non-empty text-array domain rejects an empty array and a null array.
     */
    @Test
    void nonEmptyTextArrayRejectsEmptyOrNull() {
        assertDomainViolation(() -> evaluate("'{}'::text[]::dml_utils.non_empty_text_array", Object.class));
        assertDomainViolation(() -> evaluate("NULL::dml_utils.non_empty_text_array", Object.class));
    }

    /**
     * The non-empty, non-null-element text-array domain accepts an array whose
     * elements are all non-null.
     */
    @Test
    void nonEmptyNonNullTextArrayAcceptsNonNullElements() {
        assertEquals(2, evaluate(
                "pg_catalog.cardinality(ARRAY['a', 'b']::dml_utils.non_empty_non_null_text_array)", Integer.class));
    }

    /**
     * The non-empty, non-null-element text-array domain rejects an array with a
     * null element, an empty array, and a null array.
     */
    @Test
    void nonEmptyNonNullTextArrayRejectsNullElementEmptyOrNull() {
        assertDomainViolation(() -> evaluate(
                "ARRAY['a', NULL]::text[]::dml_utils.non_empty_non_null_text_array", Object.class));
        assertDomainViolation(() -> evaluate(
                "'{}'::text[]::dml_utils.non_empty_non_null_text_array", Object.class));
        assertDomainViolation(() -> evaluate(
                "NULL::dml_utils.non_empty_non_null_text_array", Object.class));
    }

    /**
     * The non-empty, non-null-element boolean-array domain accepts an array
     * whose elements are all non-null.
     */
    @Test
    void nonEmptyNonNullBooleanArrayAcceptsNonNullElements() {
        assertEquals(2, evaluate(
                "pg_catalog.cardinality(ARRAY[true, false]::dml_utils.non_empty_non_null_boolean_array)",
                Integer.class));
    }

    /**
     * The non-empty, non-null-element boolean-array domain rejects an array with
     * a null element, an empty array, and a null array.
     */
    @Test
    void nonEmptyNonNullBooleanArrayRejectsNullElementEmptyOrNull() {
        assertDomainViolation(() -> evaluate(
                "ARRAY[true, NULL]::boolean[]::dml_utils.non_empty_non_null_boolean_array", Object.class));
        assertDomainViolation(() -> evaluate(
                "'{}'::boolean[]::dml_utils.non_empty_non_null_boolean_array", Object.class));
        assertDomainViolation(() -> evaluate(
                "NULL::dml_utils.non_empty_non_null_boolean_array", Object.class));
    }

    private <T> T evaluate(String expression, Class<T> type) {
        Record record = dsl.fetchOne("SELECT " + expression);
        return record.get(0, type);
    }
}
