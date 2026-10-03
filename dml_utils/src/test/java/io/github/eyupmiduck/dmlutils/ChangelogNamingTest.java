package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the naming conventions enforced on the Liquibase changelog. SQL
 * files must start with a three-digit, zero-padded prefix (for example
 * {@code 001-create-schema}), followed by a hyphen or an underscore. ChangeSet
 * ids either follow the same {@code NNN-name} pattern (schema/table changesets)
 * or name the routine or trigger they load:
 * {@code function-<schema>.<name>}, {@code procedure-<schema>.<name>} and
 * {@code trigger-<schema>.<name>}. Overloads of one routine share a
 * changeset, so the id is the routine's schema and name, not its signature.
 */
class ChangelogNamingTest {

    /**
     * The changeSet id rule: the {@code NNN-name} pattern, or a stored-object
     * id of the form {@code function-<schema>.<name>} /
     * {@code procedure-<schema>.<name>} / {@code trigger-<schema>.<name>}.
     */
    private static final Pattern CHANGE_SET_ID = Pattern.compile(
            "^(?:\\d{3}[-_].+|(?:function|procedure|trigger)-[A-Za-z_][A-Za-z0-9_]*"
                    + "\\.[A-Za-z_][A-Za-z0-9_]*)$");

    /**
     * Scans the changelog changes directory and asserts that no SQL file name
     * violates the {@code NNN-name.sql} pattern.
     */
    @Test
    void sqlFilesHaveThreeDigitPrefix() throws IOException {
        Path changesRoot = ChangelogTestSupport.changesRoot();

        List<Path> invalid = ChangelogValidator.findInvalidlyNamedSqlFiles(changesRoot);

        assertTrue(invalid.isEmpty(), "Invalidly named SQL files: " + invalid);
    }

    /**
     * The id pattern accepts the documented ids and rejects malformed ones
     * (wrong prefix length, extra text, or invalid schema/object characters).
     */
    @Test
    void changeSetIdPatternAcceptsAndRejects() {
        for (String valid : List.of(
                "001-create-schema",
                "999-grant_privileges",
                "function-dml_utils.run_migration_chunks",
                "procedure-dml_utils.some_proc",
                "trigger-dml_utils_data.set_updated_at")) {
            assertTrue(CHANGE_SET_ID.matcher(valid).matches(), valid + " should be accepted");
        }
        for (String invalid : List.of(
                "1-short",
                "0012-too-long",
                "invalid-001-name-suffix",
                "function-dml_utils",
                "function-dml_utils.bad-name",
                "function-.name",
                "function-dml-utils.name",
                "prefix-function-dml_utils.name")) {
            assertFalse(CHANGE_SET_ID.matcher(invalid).matches(), invalid + " should be rejected");
        }
    }

    /**
     * Traverses the changelog graph from the master file and asserts that no
     * changeSet id violates the naming convention.
     */
    @Test
    void changeSetsFollowTheNamingConvention() throws IOException {
        Path changelogRoot = ChangelogTestSupport.changelogRoot();
        Path master = ChangelogTestSupport.master();

        List<ChangelogValidator.InvalidChangeSet> invalid =
                ChangelogValidator.findInvalidlyNamedChangeSets(changelogRoot, master, CHANGE_SET_ID);

        assertTrue(invalid.isEmpty(), "Invalidly named changeSets: " + invalid);
    }
}
