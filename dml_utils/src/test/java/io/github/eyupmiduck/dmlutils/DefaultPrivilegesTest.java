package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.changelogvalidator.testing.TestResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the default-privilege hardening: changeset 007 revokes PUBLIC's
 * default EXECUTE on functions and USAGE on types, and it runs before the
 * changesets that create the first types and routine, because ALTER DEFAULT
 * PRIVILEGES only affects objects created afterwards.
 */
class DefaultPrivilegesTest extends PostgresTestBase {

    private static final String CHANGES =
            "db/changelog/changes/changes.xml";

    /**
     * The default-privilege changeset is ordered before 004 (domains and the
     * chunking_strategy enum) and 005 (the first routine). File order is what
     * Liquibase applies; the changeset id keeps its historical number.
     */
    @Test
    void defaultPrivilegesAreRevokedBeforeAnyTypeOrRoutineExists() throws Exception {
        String changes = TestResources.read(CHANGES);
        int defaults = changes.indexOf("id=\"007-default-privileges\"");
        int domains = changes.indexOf("id=\"004-create-domains\"");
        int firstRoutine = changes.indexOf("id=\"005-create-migration-key-check\"");

        assertTrue(defaults >= 0, "007-default-privileges should be in changes.xml");
        assertTrue(domains > defaults,
                "007 must run before 004 creates the first types");
        assertTrue(firstRoutine > defaults,
                "007 must run before 005 creates the first routine");
    }

    /**
     * No default ACL in the application schemas grants EXECUTE on future
     * functions or USAGE on future types to PUBLIC (grantee 0).
     */
    @Test
    void noDefaultAclGrantsAccessToPublic() {
        int publicDefaults = dsl.fetchOne("""
                        SELECT count(*)
                        FROM pg_catalog.pg_default_acl AS d
                        JOIN pg_catalog.pg_namespace AS n ON n.oid = d.defaclnamespace
                        CROSS JOIN LATERAL pg_catalog.aclexplode(d.defaclacl) AS a
                        WHERE n.nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data')
                          AND d.defaclobjtype IN ('f', 'T')
                          AND a.grantee = 0
                        """)
                .get(0, Integer.class);

        assertEquals(0, publicDefaults,
                "PUBLIC must not hold a default EXECUTE/USAGE grant in the application schemas");
    }
}
