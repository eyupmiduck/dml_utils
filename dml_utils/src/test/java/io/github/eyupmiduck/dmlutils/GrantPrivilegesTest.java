package io.github.eyupmiduck.dmlutils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the caller grants: {@code dml_utils_caller} can execute every
 * callable routine and use the migration key type, and no routine is
 * executable by {@code PUBLIC}.
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
                            WHERE n.nspname IN ('dml_utils', 'dml_utils_lib')
                              AND p.prokind = 'f'
                              AND (
                                  p.proacl IS NULL
                                  OR EXISTS (
                                      SELECT 1
                                      FROM unnest(p.proacl) AS a
                                      WHERE a::text LIKE '=X/%'
                                  )
                              )
                        )
                        """)
                .get(0, Boolean.class);

        assertFalse(Boolean.TRUE.equals(anyExecutableByPublic),
                "every routine should have an explicit ACL without PUBLIC execute");
    }

    /**
     * The caller role can execute every non-trigger routine.
     */
    @Test
    void callerCanExecuteEveryCallableRoutine() {
        Integer notExecutable = dsl.fetchOne(
                """
                        SELECT count(*)::int
                        FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname IN ('dml_utils', 'dml_utils_lib')
                          AND p.prokind = 'f'
                          AND p.prorettype <> 'trigger'::regtype
                          AND NOT pg_catalog.has_function_privilege(
                              'dml_utils_caller', p.oid, 'EXECUTE')
                        """)
                .get(0, Integer.class);

        assertEquals(0, notExecutable, "the caller should be able to execute every callable routine");
    }

    /**
     * The caller role can use the migration key type and the migration tables.
     */
    @Test
    void callerCanUseTheMigrationKeyTypeAndTables() {
        assertTrue(Boolean.TRUE.equals(dsl.fetchOne(
                        "SELECT pg_catalog.has_type_privilege('dml_utils_caller',"
                                + " 'dml_utils.migration_key', 'USAGE')")
                .get(0, Boolean.class)),
                "the caller should have USAGE on dml_utils.migration_key");
        assertTrue(Boolean.TRUE.equals(dsl.fetchOne(
                        "SELECT pg_catalog.has_table_privilege('dml_utils_caller',"
                                + " 'dml_utils.migration_boundary', 'SELECT')")
                .get(0, Boolean.class)),
                "the caller should be able to select migration_boundary");
    }
}