package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every SQL file in the Liquibase changelog is referenced by a
 * changelog XML file reachable from the master changelog, so no SQL file is
 * orphaned.
 */
class ChangelogSqlFilesTest {

    /**
     * Traverses the changelog graph from the master file and asserts that no
     * {@code .sql} file is orphaned (unreferenced by any reachable changelog
     * XML).
     */
    @Test
    void noOrphanedSqlFiles() throws IOException {
        Path changelogRoot = ChangelogTestSupport.changelogRoot();
        Path master = ChangelogTestSupport.master();

        // Sanity check so the assertion below cannot pass while the graph
        // references nothing (a misresolved root or an empty changelog).
        List<Path> referenced = ChangelogValidator.findReferencedSqlFiles(changelogRoot, master);
        assertFalse(referenced.isEmpty(), "expected the changelog graph to reference SQL files");

        List<Path> orphaned = ChangelogValidator.findOrphanedSqlFiles(changelogRoot, master);

        assertTrue(orphaned.isEmpty(), "Orphaned SQL files: " + orphaned);
    }
}
