package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.RoutineAssertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

/**
 * Runs the {@code plpgsql_check} static analyser over every routine in the
 * {@code dml_utils}, {@code dml_utils_lib} and {@code dml_utils_data} schemas,
 * failing on any finding not accepted in {@code plpgsql-check-whitelist.yml}.
 *
 * <p>The extension is compiled into the custom image and installed into the
 * template database, so each cloned test database (and the dev database, via
 * {@code docker/postgres/roles.sql}) has it.
 */
class PlpgsqlCheckTest extends PostgresTestBase {

    private static final String WHITELIST = "plpgsql-check-whitelist.yml";

    /**
     * Every plpgsql_check finding matches a whitelist entry, and every
     * whitelist entry matches a finding (so the whitelist cannot go stale).
     */
    @Test
    void routinesPassPlpgsqlCheck() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertRoutinesPassPlpgsqlCheck(
                    owner, List.of("dml_utils", "dml_utils_lib", "dml_utils_data"), WHITELIST);
        }
    }
}
