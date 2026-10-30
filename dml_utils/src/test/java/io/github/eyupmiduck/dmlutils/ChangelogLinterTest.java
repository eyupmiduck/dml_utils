package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.ChangelogLinterContract;

/**
 * Runs the Liquibase changelog linter over the module changelog, inheriting the
 * shared {@link ChangelogLinterContract}: it lints with the rules and whitelist
 * from {@code .liquibase-linter.yml} / {@code .liquibase-linter-whitelist.yml}
 * exactly as the {@code liquibase-linter} verify gate does, and checks that the
 * gate would actually fail on a bad changeset.
 *
 * <p>The linter rules combine the SQL tokens with Liquibase changeset semantics
 * (for example {@code runInTransaction}), which SQLFluff and plpgsql_check
 * cannot see.
 */
class ChangelogLinterTest extends ChangelogLinterContract {
}
