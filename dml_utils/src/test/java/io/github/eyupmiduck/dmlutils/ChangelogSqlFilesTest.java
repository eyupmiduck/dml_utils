package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogAssertions;
import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Verifies that every SQL file in the Liquibase changelog is referenced by a
 * changelog XML file reachable from the master changelog, so no SQL file is
 * orphaned. Delegates to the shared
 * {@link ChangelogAssertions#assertNoOrphanedSqlFiles}.
 */
class ChangelogSqlFilesTest {

    /**
     * Every {@code .sql} file found by an independent filesystem walk is reached
     * by the changelog graph, and the validator reports no orphaned files.
     */
    @Test
    void noOrphanedSqlFiles() throws IOException {
        ChangelogAssertions.assertNoOrphanedSqlFiles(
                ChangelogTestSupport.changelogRoot(), ChangelogTestSupport.master());
    }
}
