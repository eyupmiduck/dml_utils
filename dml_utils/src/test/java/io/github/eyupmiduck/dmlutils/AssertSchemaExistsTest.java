package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code dml_utils_lib.assert_schema_exists}: it returns normally for an
 * existing schema and raises invalid_schema_name ({@code 3F000}) for a missing
 * one.
 */
class AssertSchemaExistsTest extends PostgresTestBase {

    /**
     * An existing schema is accepted without error.
     */
    @Test
    void acceptsAnExistingSchema() {
        Routines.assertSchemaExists(dsl.configuration(), "dml_utils_fixtures");
    }

    /**
     * A missing schema raises {@code 3F000}.
     */
    @Test
    void rejectsAMissingSchema() {
        assertSqlState("3F000", () -> Routines.assertSchemaExists(dsl.configuration(), "no_such_schema"));
    }
}
