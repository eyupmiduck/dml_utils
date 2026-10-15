package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils_lib.Routines;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code dml_utils_lib.assert_table_exists}: it returns normally for an
 * existing table and raises undefined_table ({@code 42P01}) for a missing one.
 */
class AssertTableExistsTest extends PostgresTestBase {

    /**
     * An existing table is accepted without error.
     */
    @Test
    void acceptsAnExistingTable() {
        Routines.assertTableExists(dsl.configuration(), "dml_utils_fixtures", "test_bigint");
    }

    /**
     * A missing table raises {@code 42P01}.
     */
    @Test
    void rejectsAMissingTable() {
        assertSqlState("42P01", () -> Routines.assertTableExists(
                dsl.configuration(), "dml_utils_fixtures", "no_such_table"));
    }
}
