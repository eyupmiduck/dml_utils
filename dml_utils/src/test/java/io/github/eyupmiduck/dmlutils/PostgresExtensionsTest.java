package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the extensions compiled into the custom PostgreSQL image are
 * available and installed in every test database: {@code plpgsql_check} for
 * static analysis and {@code pg_background} for the chunking routines.
 */
class PostgresExtensionsTest extends PostgresTestBase {

    /**
     * Both extensions are available to install.
     */
    @Test
    void extensionsAreAvailable() {
        assertTrue(extensionAvailable("plpgsql_check"), "plpgsql_check should be available");
        assertTrue(extensionAvailable("pg_background"), "pg_background should be available");
    }

    /**
     * Both extensions are installed in the test database cloned from the
     * migrated template.
     */
    @Test
    void extensionsAreInstalled() {
        assertTrue(extensionInstalled("plpgsql_check"), "plpgsql_check should be installed");
        assertTrue(extensionInstalled("pg_background"), "pg_background should be installed");
    }

    /**
     * {@code pg_background} exposes a working SQL API: a run completes without
     * error in the test database.
     */
    @Test
    void pgBackgroundRunWorks() {
        org.jooq.Record row = dsl.fetchOne(
                "SELECT completed FROM pg_background_run('SELECT 1')");
        assertNotNull(row, "pg_background_run should return a row");

        assertEquals(Boolean.TRUE, row.get("completed", Boolean.class),
                "pg_background_run should complete");
    }

    private boolean extensionAvailable(String name) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = ?)", name));
    }

    private boolean extensionInstalled(String name) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = ?)", name));
    }
}
