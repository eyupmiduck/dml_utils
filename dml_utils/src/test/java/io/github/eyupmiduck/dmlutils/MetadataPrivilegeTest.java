package io.github.eyupmiduck.dmlutils;

import io.github.eyupmiduck.dmlutils.jooq.dml_utils.Routines;
import io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.enums.ChunkingStrategy;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationError.MIGRATION_ERROR;
import static io.github.eyupmiduck.dmlutils.jooq.dml_utils_data.tables.MigrationRun.MIGRATION_RUN;
import static io.github.eyupmiduck.dmlutils.jooqfixtures.tables.TestBigint.TEST_BIGINT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the metadata-privilege boundary: the caller can read migration
 * metadata and run chunk SQL as itself, but cannot write metadata directly.
 * Chunk SQL remains SECURITY INVOKER; metadata writes go through the
 * owner-owned helpers.
 */
class MetadataPrivilegeTest extends PostgresTestBase {

    private static final String ROLE_TEMPLATE =
            "UPDATE <driving_table> SET payload = current_user WHERE <chunking_clause>";

    private static void setRole(Connection connection, String role) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
        }
    }

    @BeforeEach
    void resetFixtures() {
        dsl.deleteFrom(MIGRATION_RUN).execute();
        dsl.truncate(TEST_BIGINT).execute();
    }

    /**
     * The caller can read metadata but has no direct DML on the migration
     * tables.
     */
    @Test
    void callerCannotWriteMetadataDirectly() throws Exception {
        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            assertNotNull(caller.fetchOne(
                    "SELECT count(*) FROM dml_utils_data.migration_run"));
            assertSqlState("42501", () -> caller.execute(
                    "UPDATE dml_utils_data.migration_run SET label = label"));
            assertSqlState("42501", () -> caller.execute(
                    "DELETE FROM dml_utils_data.migration_boundary"));
        }
    }

    /**
     * The invoker-owned worker runs the chunk SQL as the caller. The helper
     * writes the metadata, but the SQL itself sees current_user = caller.
     */
    @Test
    void chunkSqlRunsAsTheCaller() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("caller-role");

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            Routines.runMigrationChunks(caller.configuration(), ROLE_TEMPLATE,
                    TEST_BIGINT.getSchema().getName(), TEST_BIGINT.getName(), label,
                    2, 1, "t", ChunkingStrategy.primary_key, 1, 0);
        }

        assertEquals(3, dsl.fetchCount(TEST_BIGINT,
                        TEST_BIGINT.PAYLOAD.eq("dml_utils_caller")),
                "the chunk SQL should execute with the caller role, not the owner");
        assertTrue(runCompleted(label), "the run should complete");
    }

    /**
     * Direct metadata DML in a chunk template fails; the run records the
     * failure and does not complete.
     */
    @Test
    void chunkTemplateCannotWriteMetadataDirectly() throws Exception {
        seedBigint(1, 2, 3);
        String label = uniqueLabel("caller-metadata");
        String malicious = "UPDATE <driving_table> SET payload = 'done' WHERE <chunking_clause>;"
                + " UPDATE dml_utils_data.migration_run SET label = 'tampered'"
                + " WHERE label = '" + label + "'";

        try (Connection connection = openTestConnection()) {
            connection.setAutoCommit(true);
            setRole(connection, "dml_utils_caller");
            DSLContext caller = DSL.using(connection, SQLDialect.POSTGRES);

            assertSqlState("42501", () -> Routines.runMigrationChunks(
                    caller.configuration(), malicious, TEST_BIGINT.getSchema().getName(),
                    TEST_BIGINT.getName(), label, 2, 1, "t",
                    ChunkingStrategy.primary_key, 1, 0));
        }

        assertFalse(runCompleted(label), "the failed run must not be complete");
        assertEquals(1, dsl.fetchCount(MIGRATION_RUN, MIGRATION_RUN.LABEL.eq(label)),
                "the tampering statement must not change the run label");
        assertEquals(1, dsl.fetchCount(MIGRATION_ERROR,
                        MIGRATION_ERROR.RUN_ID.eq(runId(label))),
                "the chunk failure should be recorded by the owner-owned helper");
    }

    private long runId(String label) {
        return dsl.select(MIGRATION_RUN.RUN_ID)
                .from(MIGRATION_RUN)
                .where(MIGRATION_RUN.LABEL.eq(label))
                .fetchOne(MIGRATION_RUN.RUN_ID);
    }
}
