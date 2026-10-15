package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TrackingTableAssertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

/**
 * Verifies that Liquibase keeps its tracking tables out of the application
 * schemas: they live in the dedicated {@code liquibase} schema, prefixed with
 * {@code dml_utils_}, rather than as the generic {@code databasechangelog} /
 * {@code databasechangeloglock} tables. Delegates to the shared
 * {@link TrackingTableAssertions}.
 */
class LiquibaseTrackingTablesTest extends PostgresTestBase {

    /**
     * The tracking tables exist in the {@code liquibase} schema with the
     * {@code dml_utils_} prefix, and the generic names are absent from every
     * application schema.
     */
    @Test
    void trackingTablesLiveInTheLiquibaseSchema() throws Exception {
        try (Connection connection = openTestConnection()) {
            TrackingTableAssertions.assertTrackingTablesAreIsolated(connection, LIQUIBASE_SCHEMA,
                    DATABASE_CHANGELOG_TABLE, DATABASE_CHANGELOG_LOCK_TABLE,
                    List.of(PUBLIC_SCHEMA, "dml_utils", "dml_utils_lib", "dml_utils_data"));
        }
    }
}
