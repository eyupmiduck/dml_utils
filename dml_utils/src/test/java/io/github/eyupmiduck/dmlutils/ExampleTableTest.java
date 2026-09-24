package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the Liquibase changelog loads the example table: the expected
 * columns exist, and the shared {@code set_updated_at()} trigger keeps
 * {@code updated_at} current.
 */
class ExampleTableTest extends PostgresTestBase {

    /**
     * The example table exists in the {@code dml_utils} schema with the
     * expected columns.
     */
    @Test
    void exampleTableIsLoaded() {
        assertTrue(tableExists("dml_utils", "example"), "dml_utils.example should exist");
        assertTrue(hasColumn("dml_utils", "example", "id"), "id should exist");
        assertTrue(hasColumn("dml_utils", "example", "name"), "name should exist");
        assertTrue(hasColumn("dml_utils", "example", "created_at"), "created_at should exist");
        assertTrue(hasColumn("dml_utils", "example", "updated_at"), "updated_at should exist");
    }

    /**
     * The {@code updated_at} trigger is attached and overwrites a
     * caller-supplied value on UPDATE, so a caller cannot bypass it.
     */
    @Test
    void updatedAtIsMaintainedByTheTrigger() {
        assertTrue(triggerExists("dml_utils", "example", "example_set_updated_at"),
                "example_set_updated_at should be attached to dml_utils.example");

        dsl.execute("INSERT INTO dml_utils.example (name) VALUES ('a')");
        dsl.execute("UPDATE dml_utils.example"
                + " SET name = 'b', updated_at = timestamptz '2000-01-01 00:00:00+00'"
                + " WHERE name = 'a'");

        Object recent = dsl.fetchValue(
                "SELECT updated_at > timestamptz '2020-01-01 00:00:00+00'"
                        + " FROM dml_utils.example WHERE name = 'b'");
        assertTrue(Boolean.TRUE.equals(recent),
                "the trigger should overwrite updated_at with the transaction timestamp");
    }
}
