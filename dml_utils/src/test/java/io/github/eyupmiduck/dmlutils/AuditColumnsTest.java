package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.RoutineAssertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

/**
 * Applies the shared audit-column convention to the {@code dml_utils_data}
 * tables: {@code created_at}/{@code updated_at} are {@code timestamptz NOT NULL
 * DEFAULT now()}, and an enabled {@code BEFORE UPDATE} row trigger refreshes
 * {@code updated_at}. The behavioral probe for {@code migration_run} lives in
 * {@link MigrationTablesTest}.
 */
class AuditColumnsTest extends PostgresTestBase {

    /**
     * Every migration table satisfies the catalog convention, so the shared
     * check reports nothing.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void migrationTablesFollowTheAuditColumnConvention() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertAuditColumnsFollowConvention(owner, List.of("dml_utils_data"));
        }
    }
}
