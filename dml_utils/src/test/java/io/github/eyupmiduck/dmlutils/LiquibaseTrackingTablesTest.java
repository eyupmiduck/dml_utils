package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Liquibase keeps its tracking tables out of the application
 * schemas: they live in the dedicated {@code liquibase} schema, prefixed with
 * {@code dml_utils_}, rather than as the generic {@code databasechangelog} /
 * {@code databasechangeloglock} tables.
 */
class LiquibaseTrackingTablesTest extends PostgresTestBase {

    /**
     * Asserts that the tracking tables exist in the {@code liquibase} schema
     * with the {@code dml_utils_} prefix, and that the generic names are absent
     * from the {@code public} and {@code dml_utils} schemas.
     */
    @Test
    void trackingTablesLiveInTheLiquibaseSchema() {
        assertTrue(relationExists(LIQUIBASE_SCHEMA, DATABASE_CHANGELOG_TABLE),
                "expected " + LIQUIBASE_SCHEMA + "." + DATABASE_CHANGELOG_TABLE);
        assertTrue(relationExists(LIQUIBASE_SCHEMA, DATABASE_CHANGELOG_LOCK_TABLE),
                "expected " + LIQUIBASE_SCHEMA + "." + DATABASE_CHANGELOG_LOCK_TABLE);

        assertFalse(relationExists("public", "databasechangelog"),
                "the generic public.databasechangelog should not exist");
        assertFalse(relationExists("public", "databasechangeloglock"),
                "the generic public.databasechangeloglock should not exist");
        assertFalse(relationExists("dml_utils", "databasechangelog"),
                "the generic dml_utils.databasechangelog should not exist");
        assertFalse(relationExists("dml_utils", "databasechangeloglock"),
                "the generic dml_utils.databasechangeloglock should not exist");
    }
}
