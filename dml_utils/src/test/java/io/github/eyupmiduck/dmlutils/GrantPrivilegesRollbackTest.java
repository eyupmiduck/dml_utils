package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the {@code 999-grant-privileges} rollback mirrors the forward
 * grants: every function the forward changeset grants to {@code dml_utils_caller}
 * is revoked from the caller in the rollback, and every function whose default
 * {@code PUBLIC} execute the forward revokes has its {@code PUBLIC} grant
 * restored. Keeping the two files in step means a signature change cannot leave
 * the rollback referencing a stale function.
 */
class GrantPrivilegesRollbackTest {

    private static final String FORWARD =
            "db/changelog/changes/sql_changes/999-grant-privileges.sql";
    private static final String ROLLBACK =
            "db/changelog/changes/rollback/999-grant-privileges-rollback.sql";

    private static void assertMirrors(String forwardRegex, String rollbackRegex, String message)
            throws IOException {
        Set<String> forward = extract(FORWARD, forwardRegex);
        Set<String> rollback = extract(ROLLBACK, rollbackRegex);

        Set<String> missing = new LinkedHashSet<>(forward);
        missing.removeAll(rollback);
        assertTrue(missing.isEmpty(), () -> message + "; missing: " + missing);
    }

    /**
     * Extracts the function signatures matched by {@code regex}, normalised to
     * single spaces so formatting differences between the two files do not matter.
     */
    private static Set<String> extract(String resource, String regex) throws IOException {
        Set<String> signatures = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(read(resource));
        while (matcher.find()) {
            signatures.add(matcher.group(1).replaceAll("\\s+", " ").trim());
        }
        return signatures;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = GrantPrivilegesRollbackTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "resource not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Every function granted to the caller in the forward changeset is revoked
     * from the caller in the rollback, with the same signature.
     */
    @Test
    void rollbackRevokesEveryFunctionGrantedToTheCaller() throws IOException {
        assertMirrors("GRANT EXECUTE ON FUNCTION\\s+(.*?)\\s+TO dml_utils_caller;",
                "REVOKE EXECUTE ON FUNCTION\\s+(.*?)\\s+FROM dml_utils_caller;",
                "every function granted to the caller should be revoked in the rollback");
    }

    /**
     * Every function whose default {@code PUBLIC} execute the forward revokes has
     * a matching {@code PUBLIC} grant restored in the rollback (including the
     * trigger functions, which the forward revokes from {@code PUBLIC} and grants
     * to no one).
     */
    @Test
    void rollbackRestoresPublicExecuteForEveryFunctionRevokedFromPublic() throws IOException {
        assertMirrors("REVOKE EXECUTE ON FUNCTION\\s+(.*?)\\s+FROM public;",
                "GRANT EXECUTE ON FUNCTION\\s+(.*?)\\s+TO public;",
                "every function revoked from PUBLIC should have its PUBLIC grant restored");
    }
}
