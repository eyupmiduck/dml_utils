package io.github.eyupmiduck.dmlutils;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the fixture grant changeset's default privileges: a table created
 * later in {@code dml_utils_fixtures} by the creator role is usable by the test
 * role without an extra explicit grant, so runtime fixture DDL cannot produce an
 * order-dependent "permission denied".
 */
class FixtureDefaultPrivilegesTest extends PostgresTestBase {

    /**
     * A table created by {@code dml_utils_owner} after the fixture changelog is
     * granted to {@code dml_utils_test} by the default ACL.
     */
    @Test
    void tablesCreatedLaterAreUsableByTheTestRole() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
            ownerDsl.execute("CREATE TABLE dml_utils_fixtures.default_acl_probe (id integer)");
            try {
                assertEquals(Boolean.TRUE, dsl.fetchOne(
                                "SELECT pg_catalog.has_table_privilege(?,"
                                        + " 'dml_utils_fixtures.default_acl_probe',"
                                        + " 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')",
                                TEST_USER)
                        .get(0, Boolean.class),
                        "the test role should inherit the fixture default privileges");

                dsl.execute("INSERT INTO dml_utils_fixtures.default_acl_probe (id) VALUES (1)");
                assertEquals(1, dsl.fetchCount(DSL.table("dml_utils_fixtures.default_acl_probe")),
                        "the test role should be able to use the table");
            } finally {
                ownerDsl.execute("DROP TABLE IF EXISTS dml_utils_fixtures.default_acl_probe");
            }
        }
    }
}
