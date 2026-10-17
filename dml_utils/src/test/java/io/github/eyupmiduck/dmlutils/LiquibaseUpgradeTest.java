package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.PublishedRelease;
import io.github.eyupmiduck.changelogvalidator.testing.UpgradeTestBase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Exercises the incremental upgrade from the most recent published release of
 * dml_utils to the current codebase: the released changelog's SQL is applied to
 * a scratch database, then the current changelog, so an upgrade that only works
 * on an empty database fails here.
 *
 * <p>It reuses the shared {@link UpgradeTestBase} and the dml_utils container
 * configuration (see {@link PostgresTestBase}); the previous release is resolved
 * from GitHub Packages automatically.
 */
class LiquibaseUpgradeTest extends UpgradeTestBase {

    @Override
    protected PublishedRelease.Coordinates releaseCoordinates() {
        return new PublishedRelease.Coordinates(
                "https://maven.pkg.github.com/eyupmiduck/dml_utils",
                "io.github.eyupmiduck", "dml_utils",
                System.getProperty("upgrade.current.version", "0.0.0-SNAPSHOT"));
    }

    /**
     * Installs the same extensions as the template, so the container's shared
     * template is identical whichever test class prepares it first.
     */
    @Override
    protected void installExtensions(String database) throws SQLException {
        try (Connection admin = openAdminConnection(database)) {
            PostgresTestBase.installProjectExtensions(admin);
        }
    }

    @Override
    protected String defaultPostgresImage() {
        return PostgresTestBase.POSTGRES_IMAGE;
    }

    @Override
    protected String databaseName() {
        return PostgresTestBase.DATABASE;
    }

    @Override
    protected String ownerUser() {
        return PostgresTestBase.OWNER_USER;
    }

    @Override
    protected String ownerPassword() {
        return PostgresTestBase.OWNER_PASSWORD;
    }

    @Override
    protected String testUser() {
        return PostgresTestBase.TEST_USER;
    }

    @Override
    protected String testPassword() {
        return PostgresTestBase.TEST_PASSWORD;
    }

    @Override
    protected String fixturesResource() {
        return PostgresTestBase.FIXTURES_RESOURCE;
    }

    @Override
    protected String databaseChangeLogTableName() {
        return PostgresTestBase.DATABASE_CHANGELOG_TABLE;
    }

    @Override
    protected String databaseChangeLogLockTableName() {
        return PostgresTestBase.DATABASE_CHANGELOG_LOCK_TABLE;
    }

    /**
     * The published release contributes changesets, the current changelog
     * applies cleanly on top, and the upgraded database has the dml_utils
     * schemas.
     */
    @Test
    void upgradesThePublishedReleaseToTheCurrentChangelog() {
        assertFalse(report().previousRelease().isEmpty(),
                "the published release should apply at least one changeset");
        assertEquals(3, upgradeDsl().fetchOne(
                        "SELECT count(*) FROM pg_namespace "
                                + "WHERE nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data')")
                .get(0, Integer.class),
                "the upgraded database should have the dml_utils schemas");
    }
}
