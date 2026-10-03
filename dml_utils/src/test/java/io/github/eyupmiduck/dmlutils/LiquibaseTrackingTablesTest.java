package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Liquibase keeps its tracking tables out of the application
 * schemas: they live in the dedicated {@code liquibase} schema, prefixed with
 * {@code dml_utils_}, rather than as the generic {@code databasechangelog} /
 * {@code databasechangeloglock} tables.
 */
class LiquibaseTrackingTablesTest extends PostgresTestBase {

    // Independent literals: deliberately not the constants PostgresTestBase
    // passes to Liquibase, so a regression in that configuration still fails
    // this test.
    private static final String CHANGELOG = "dml_utils_databasechangelog";
    private static final String CHANGELOG_LOCK = "dml_utils_databasechangeloglock";
    private static final List<String> APPLICATION_SCHEMAS =
            List.of("public", "dml_utils", "dml_utils_lib", "dml_utils_data");

    /**
     * Asserts that the tracking tables exist in the {@code liquibase} schema
     * with the {@code dml_utils_} prefix, and that the generic names are absent
     * from every application schema.
     */
    @Test
    void trackingTablesLiveInTheLiquibaseSchema() {
        assertTrue(tableExists(LIQUIBASE_SCHEMA, CHANGELOG),
                "expected " + LIQUIBASE_SCHEMA + "." + CHANGELOG);
        assertTrue(tableExists(LIQUIBASE_SCHEMA, CHANGELOG_LOCK),
                "expected " + LIQUIBASE_SCHEMA + "." + CHANGELOG_LOCK);

        for (String schema : APPLICATION_SCHEMAS) {
            assertFalse(tableExists(schema, "databasechangelog"),
                    "the generic " + schema + ".databasechangelog should not exist");
            assertFalse(tableExists(schema, "databasechangeloglock"),
                    "the generic " + schema + ".databasechangeloglock should not exist");
        }
    }
}
