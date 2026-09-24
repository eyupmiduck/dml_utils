package io.github.eyupmiduck.dmlutils;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Classpath access to the Liquibase changelog, shared by the static
 * (non-container) changelog tests and the container base.
 */
final class ChangelogTestSupport {

    /**
     * The master changelog resource path, relative to the classpath root.
     */
    static final String MASTER_RESOURCE = "db/changelog/db.changelog-master.xml";

    private ChangelogTestSupport() {
    }

    /**
     * Returns the changelog root directory.
     *
     * @return the {@code db/changelog} directory
     */
    static Path changelogRoot() {
        return classpathDir("db/changelog");
    }

    /**
     * Returns the changes directory (the changelog XML and the SQL files).
     *
     * @return the {@code db/changelog/changes} directory
     */
    static Path changesRoot() {
        return classpathDir("db/changelog/changes");
    }

    /**
     * Returns the master changelog file.
     *
     * @return the master changelog path
     */
    static Path master() {
        return changelogRoot().resolve(Path.of(MASTER_RESOURCE).getFileName());
    }

    private static Path classpathDir(String resource) {
        URL url = ChangelogTestSupport.class.getClassLoader().getResource(resource);
        Objects.requireNonNull(url, resource + " must be on the test classpath");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("invalid classpath URL for " + resource, e);
        }
    }
}
