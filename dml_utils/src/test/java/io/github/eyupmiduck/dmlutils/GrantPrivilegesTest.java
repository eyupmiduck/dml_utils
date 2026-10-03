package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the caller grants: {@code dml_utils_caller} can execute every
 * callable routine (ordinary and trigger-returning functions, plus procedures)
 * and use the migration key type, and that no routine or type is usable by
 * {@code PUBLIC}.
 */
class GrantPrivilegesTest extends PostgresTestBase {

    /**
     * No routine in the application schemas keeps PostgreSQL's default
     * {@code PUBLIC} execute privilege.
     */
    @Test
    void noRoutineIsExecutableByPublic() {
        Boolean anyExecutableByPublic = dsl.fetchOne(
                        """
                                SELECT EXISTS (
                                    SELECT 1
                                    FROM pg_proc p
                                    JOIN pg_namespace n ON n.oid = p.pronamespace
                                    WHERE n.nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data')
                                      AND p.prokind IN ('f', 'p')
                                      AND (
                                          p.proacl IS NULL
                                          OR EXISTS (
                                              SELECT 1
                                              FROM unnest(p.proacl) AS a
                                              -- The grantee is empty ("=X/..."), and a
                                              -- trailing "*" marks WITH GRANT OPTION.
                                              WHERE a::text LIKE '=X%'
                                          )
                                      )
                                )
                                """)
                .get(0, Boolean.class);

        assertFalse(Boolean.TRUE.equals(anyExecutableByPublic),
                "every routine should have an explicit ACL without PUBLIC execute");
    }

    /**
     * The application's types and domains do not retain PostgreSQL's default
     * {@code PUBLIC} usage privilege.
     */
    @Test
    void applicationTypesAreNotUsableByPublic() {
        Integer usableByPublic = dsl.fetchOne(
                        """
                                SELECT count(*)::int
                                FROM pg_type t
                                JOIN pg_namespace n ON n.oid = t.typnamespace
                                WHERE (n.nspname, t.typname) IN (
                                    ('dml_utils_data', 'migration_key'),
                                    ('dml_utils_data', 'non_null_text'),
                                    ('dml_utils_data', 'positive_integer'))
                                  AND (
                                      t.typacl IS NULL
                                      OR EXISTS (
                                          SELECT 1
                                          FROM unnest(t.typacl) AS a
                                          -- "=U..." is the PUBLIC entry; a trailing "*"
                                          -- marks WITH GRANT OPTION.
                                          WHERE a::text LIKE '=U%'
                                      )
                                  )
                                """)
                .get(0, Integer.class);

        assertEquals(0, usableByPublic,
                "application types should have an explicit ACL without PUBLIC usage");
    }

    /**
     * The caller role can execute every routine it is meant to invoke: every
     * procedure, and every function except trigger functions (whose EXECUTE is
     * deliberately granted to no one).
     */
    @Test
    void callerCanExecuteEveryCallableRoutine() {
        Integer notExecutable = dsl.fetchOne(
                        """
                                SELECT count(*)::int
                                FROM pg_proc p
                                JOIN pg_namespace n ON n.oid = p.pronamespace
                                WHERE n.nspname IN ('dml_utils', 'dml_utils_lib', 'dml_utils_data')
                                  AND p.prokind IN ('f', 'p')
                                  AND (p.prokind = 'p' OR p.prorettype <> 'trigger'::regtype)
                                  AND NOT pg_catalog.has_function_privilege(
                                      'dml_utils_caller', p.oid, 'EXECUTE')
                                """)
                .get(0, Integer.class);

        assertEquals(0, notExecutable, "the caller should be able to execute every callable routine");
    }

    /**
     * The caller has an explicit {@code USAGE} grant on the migration key type
     * (asserted on the ACL, not via {@code has_type_privilege}, which would also
     * accept the default PUBLIC grant) and can select the migration tables.
     */
    @Test
    void callerHasExplicitUsageOnTheMigrationKeyTypeAndCanSelectTables() {
        Boolean explicitUsage = dsl.fetchOne(
                        """
                                SELECT EXISTS (
                                    SELECT 1
                                    FROM pg_type t
                                    JOIN pg_namespace n ON n.oid = t.typnamespace
                                    CROSS JOIN LATERAL unnest(
                                        coalesce(t.typacl, '{}'::aclitem[])) AS a
                                    WHERE n.nspname = 'dml_utils_data'
                                      AND t.typname = 'migration_key'
                                      AND a::text LIKE 'dml_utils_caller=U%'
                                )
                                """)
                .get(0, Boolean.class);
        assertTrue(Boolean.TRUE.equals(explicitUsage),
                "the caller should have an explicit USAGE grant on dml_utils_data.migration_key");

        assertTrue(Boolean.TRUE.equals(dsl.fetchOne(
                                "SELECT pg_catalog.has_table_privilege('dml_utils_caller',"
                                        + " 'dml_utils_data.migration_boundary', 'SELECT')")
                        .get(0, Boolean.class)),
                "the caller should be able to select migration_boundary");
    }
}
