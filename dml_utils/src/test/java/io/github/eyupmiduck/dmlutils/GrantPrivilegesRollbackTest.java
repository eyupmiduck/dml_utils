package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.GrantAssertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Verifies that the {@code 999-grant-privileges} rollback mirrors the forward
 * grants, delegating to the shared
 * {@link GrantAssertions#assertGrantRollbackMirrors}.
 */
class GrantPrivilegesRollbackTest {

    private static final String FORWARD =
            "db/changelog/changes/sql_changes/999-grant-privileges.sql";
    private static final String ROLLBACK =
            "db/changelog/changes/rollback/999-grant-privileges-rollback.sql";

    /**
     * Every function granted to {@code dml_utils_caller} in the forward
     * changeset is revoked from the caller in the rollback with the same
     * signature, and every function revoked from {@code PUBLIC} has its
     * {@code PUBLIC} grant restored.
     */
    @Test
    void rollbackMirrorsTheForwardGrants() throws IOException {
        GrantAssertions.assertGrantRollbackMirrors(FORWARD, "dml_utils_caller", ROLLBACK);
    }
}
