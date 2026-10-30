package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TrackingTablesContract;

import java.util.Collection;
import java.util.List;

/**
 * Verifies that Liquibase keeps its tracking tables out of the application
 * schemas: they live in the dedicated {@code liquibase} schema, prefixed with
 * {@code dml_utils_}, rather than as the generic {@code databasechangelog} /
 * {@code databasechangeloglock} tables. Inherits the shared
 * {@link TrackingTablesContract}.
 */
class LiquibaseTrackingTablesTest extends TrackingTablesContract {

    /**
     * The dml_utils container and changelog configuration.
     *
     * @return the configuration
     */
    @Override
    protected Configuration configuration() {
        return PostgresTestBase.CONFIGURATION;
    }

    /**
     * The schemas that must not hold Liquibase's generic tracking-table names.
     *
     * @return the application schemas
     */
    @Override
    protected Collection<String> applicationSchemas() {
        return List.of(PUBLIC_SCHEMA, "dml_utils", "dml_utils_lib", "dml_utils_data");
    }
}
