package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every SQL file in the Liquibase changelog is referenced by a
 * changelog XML file reachable from the master changelog, so no SQL file is
 * orphaned.
 */
class ChangelogSqlFilesTest {

    /**
     * Every {@code .sql} file found by an independent filesystem walk is reached
     * by the changelog graph, and the validator reports no orphaned files.
     */
    @Test
    void noOrphanedSqlFiles() throws IOException {
        Path changelogRoot = ChangelogTestSupport.changelogRoot();
        Path master = ChangelogTestSupport.master();

        // Enumerate the SQL files directly, rather than trusting the validator's
        // own traversal to discover them.
        Set<Path> allSql = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(changelogRoot)) {
            walk.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(allSql::add);
        }
        assertFalse(allSql.isEmpty(), "expected the changelog tree to contain SQL files");

        // The validator reports paths relative to the changelog root; resolve them
        // the same way as the independent walk so the two sets are comparable.
        Set<Path> referenced =
                ChangelogValidator.findReferencedSqlFiles(changelogRoot, master).stream()
                        .map(path -> path.isAbsolute() ? path : changelogRoot.resolve(path))
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(Collectors.toCollection(TreeSet::new));
        assertFalse(referenced.isEmpty(), "expected the changelog graph to reference SQL files");

        Set<Path> unreferenced = new TreeSet<>(allSql);
        unreferenced.removeAll(referenced);

        assertTrue(unreferenced.isEmpty(), "SQL files the changelog never reaches: " + unreferenced);
        assertTrue(ChangelogValidator.findOrphanedSqlFiles(changelogRoot, master).isEmpty(),
                "the validator should report no orphaned SQL files");
    }
}
