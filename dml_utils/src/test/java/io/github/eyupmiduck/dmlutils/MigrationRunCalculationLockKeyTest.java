package io.github.eyupmiduck.dmlutils;

import org.jooq.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code dml_utils_lib.migration_run_calculation_lock_key}: the advisory
 * lock key is declared immutable, strict and parallel safe, maps a NULL run id to
 * NULL instead of a shared key, and is deterministic per run.
 */
class MigrationRunCalculationLockKeyTest extends PostgresTestBase {

    private static final String FUNCTION =
            "dml_utils_lib.migration_run_calculation_lock_key";

    /**
     * The catalog declaration matches the documented contract: immutable, strict
     * and parallel safe.
     */
    @Test
    void isImmutableStrictAndParallelSafe() {
        Record attributes = dsl.fetchOne("""
                SELECT p.provolatile, p.proisstrict, p.proparallel
                FROM pg_catalog.pg_proc AS p
                         JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname = 'dml_utils_lib'
                  AND p.proname = 'migration_run_calculation_lock_key'
                """);

        assertEquals("i", attributes.get("provolatile", String.class),
                "the key derivation is immutable");
        assertTrue(attributes.get("proisstrict", Boolean.class),
                "STRICT keeps a NULL run id from collapsing onto a shared key");
        assertEquals("s", attributes.get("proparallel", String.class),
                "the key derivation is parallel safe");
    }

    /**
     * A NULL run id yields NULL; every NULL caller must not share one key.
     */
    @Test
    void nullRunIdYieldsNull() {
        assertNull(dsl.fetchOne("SELECT " + FUNCTION + "(NULL)")
                .get(0, Long.class));
    }

    /**
     * The same run id always maps to the same non-NULL key, and different runs
     * map to different keys.
     */
    @Test
    void derivesAStableKeyPerRun() {
        Long first = dsl.fetchOne("SELECT " + FUNCTION + "(42)")
                .get(0, Long.class);
        Long again = dsl.fetchOne("SELECT " + FUNCTION + "(42)")
                .get(0, Long.class);
        Long other = dsl.fetchOne("SELECT " + FUNCTION + "(43)")
                .get(0, Long.class);

        assertEquals(first, again, "the key is deterministic");
        assertNotEquals(first, other, "different runs use different keys");
    }
}
