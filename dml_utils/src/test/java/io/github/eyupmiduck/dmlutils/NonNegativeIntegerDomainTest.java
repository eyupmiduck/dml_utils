package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the Liquibase changelog creates the
 * {@code dml_utils_data.non_negative_integer} domain with the expected
 * constraints.
 */
class NonNegativeIntegerDomainTest extends PostgresTestBase {

    /**
     * The domain accepts zero and positive values, including the integer maximum.
     */
    @Test
    void acceptsZeroAndPositiveValues() {
        assertEquals(0, evaluate("0::dml_utils_data.non_negative_integer", Integer.class));
        assertEquals(1, evaluate("1::dml_utils_data.non_negative_integer", Integer.class));
        assertEquals(Integer.MAX_VALUE,
                evaluate("2147483647::dml_utils_data.non_negative_integer", Integer.class));
    }

    /**
     * The domain rejects negative values with a check-constraint violation.
     */
    @Test
    void rejectsNegativeValues() {
        assertDomainViolation(() -> evaluate("(-1)::dml_utils_data.non_negative_integer", Integer.class));
    }

    /**
     * The domain rejects null with a check-constraint violation.
     */
    @Test
    void rejectsNull() {
        assertDomainViolation(() -> evaluate("NULL::dml_utils_data.non_negative_integer", Integer.class));
    }
}
