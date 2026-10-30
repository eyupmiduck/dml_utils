package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogNamingContract;

/**
 * Verifies the naming conventions enforced on the Liquibase changelog, inheriting
 * the shared {@link ChangelogNamingContract}: SQL files must start with a
 * three-digit prefix, and changeSet ids must follow the shared pattern.
 */
class ChangelogNamingTest extends ChangelogNamingContract {
}
