package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogAssertions;
import io.github.eyupmiduck.changelogvalidator.testing.ChangelogTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Verifies the naming conventions enforced on the Liquibase changelog: SQL files
 * must start with a three-digit prefix, and changeSet ids must follow the shared
 * {@link ChangelogAssertions#CHANGESET_ID_PATTERN} ({@code NNN-name} for
 * schema/table changesets, or {@code function-}/{@code procedure-}/
 * {@code trigger-<schema>.<name>} for a stored object).
 */
class ChangelogNamingTest {

    /**
     * No SQL file name in the changes directory violates the
     * {@code NNN-name.sql} pattern.
     */
    @Test
    void sqlFilesHaveThreeDigitPrefix() throws IOException {
        ChangelogAssertions.assertSqlFilesAreNumbered(ChangelogTestSupport.changesRoot());
    }

    /**
     * No changeSet id reachable from the master changelog violates the shared
     * naming pattern.
     */
    @Test
    void changeSetsFollowTheNamingConvention() throws IOException {
        ChangelogAssertions.assertChangeSetsFollowNaming(
                ChangelogTestSupport.changelogRoot(), ChangelogTestSupport.master());
    }
}
