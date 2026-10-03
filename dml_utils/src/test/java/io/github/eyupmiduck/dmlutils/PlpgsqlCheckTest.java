package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.PlpgsqlCheck;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
    void routinesPassPlpgsqlCheck() throws SQLException, IOException {
        List<PlpgsqlCheck.AllowedFinding> allowed;
        try (InputStream whitelist = getClass().getClassLoader().getResourceAsStream(WHITELIST)) {
            assertNotNull(whitelist, WHITELIST + " not found on the test classpath");
            allowed = PlpgsqlCheck.loadWhitelist(whitelist);
        }

        PlpgsqlCheck.Report report;
        try (Connection owner = openOwnerConnection()) {
            report = PlpgsqlCheck.check(owner, List.of("dml_utils", "dml_utils_lib", "dml_utils_data"), allowed);
        }

        assertEquals(List.of(), report.unexpected(),
                () -> "unexpected plpgsql_check findings:\n" + report.unexpected().stream()
                        .map(PlpgsqlCheck.Finding::describe)
                        .collect(Collectors.joining("\n")));
        assertEquals(List.of(), report.stale(),
                () -> "stale whitelist entries:\n" + report.stale().stream()
                        .map(PlpgsqlCheck.AllowedFinding::describe)
                        .collect(Collectors.joining("\n")));
    }
}
