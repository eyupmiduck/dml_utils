package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the Liquibase changelog creates the
 * {@code dml_utils.migration_key_value} domain (a {@code migration_key} with
 * exactly one populated attribute) and that it rejects keys that are empty or
 * populate more than one attribute.
 */
class MigrationKeyValueDomainTest extends PostgresTestBase {

    /**
     * A key that populates only the bigint attribute is accepted and reads back
     * as a bigint.
     */
    @Test
    void acceptsABigintValue() {
        assertEquals(7L, evaluate(
                "(ROW(7::bigint, NULL, NULL)::dml_utils.migration_key_value).bigint_value",
                Long.class));
    }

    /**
     * A key that populates only the text attribute is accepted and reads back
     * as text.
     */
    @Test
    void acceptsATextValue() {
        assertEquals("abc", evaluate(
                "(ROW(NULL, 'abc'::text, NULL)::dml_utils.migration_key_value).text_value",
                String.class));
    }

    /**
     * A key that populates only the uuid attribute is accepted and reads back
     * as a uuid.
     */
    @Test
    void acceptsAUuidValue() {
        UUID uuid = new UUID(0x1122334455667788L, 0x99aabbccddeeff00L);
        assertEquals(uuid, evaluate(
                "(ROW(NULL, NULL, '11223344-5566-7788-99aa-bbccddeeff00'::uuid)"
                        + "::dml_utils.migration_key_value).uuid_value",
                UUID.class));
    }

    /**
     * A key with no populated attribute violates the domain's check constraint.
     */
    @Test
    void rejectsAnEmptyKey() {
        assertDomainViolation(() -> evaluate(
                "ROW(NULL, NULL, NULL)::dml_utils.migration_key_value", Object.class));
    }

    /**
     * A key with two populated attributes violates the domain's check
     * constraint.
     */
    @Test
    void rejectsAKeyWithTwoAttributes() {
        assertDomainViolation(() -> evaluate(
                "ROW(1::bigint, 'abc'::text, NULL)::dml_utils.migration_key_value",
                Object.class));
    }

    /**
     * A key with all three attributes populated violates the domain's check
     * constraint.
     */
    @Test
    void rejectsAKeyWithThreeAttributes() {
        assertDomainViolation(() -> evaluate(
                "ROW(1::bigint, 'abc'::text,"
                        + " '11223344-5566-7788-99aa-bbccddeeff00'::uuid)"
                        + "::dml_utils.migration_key_value",
                Object.class));
    }

}
