package io.github.eyupmiduck.dmlutils;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for tests that need a migrated PostgreSQL database.
 *
 * <p>A single PostgreSQL container is shared by all tests. On first use, the
 * roles are created by the custom image's init script and the Liquibase
 * changelog is applied once to a template database, connecting as the
 * {@code dml_utils_owner} role (like a real deployment). Each test class then
 * gets its own private database, created cheaply with
 * {@code CREATE DATABASE ... TEMPLATE ...}, and connects to it as the
 * {@code dml_utils_test} role, which is granted the {@code dml_utils_caller}
 * role. The private database is dropped after the class finishes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresTestBase {

    /**
     * The schema tests create their own tables in; the test role has CREATE on
     * it.
     */
    protected static final String PUBLIC_SCHEMA = "public";
    /**
     * The schema Liquibase keeps its tracking tables in, so they stay out of the
     * application schemas. The custom image's init script creates it for real
     * databases; {@link #prepareTemplateDatabase()} creates it for the template.
     */
    static final String LIQUIBASE_SCHEMA = "liquibase";
    static final String DATABASE_CHANGELOG_TABLE = "dml_utils_databasechangelog";
    static final String DATABASE_CHANGELOG_LOCK_TABLE = "dml_utils_databasechangeloglock";
    /**
     * The test-only fixture changelog (fixture tables the tests drive the
     * routines over), applied to the template database after the production
     * changelog.
     */
    private static final String FIXTURES_RESOURCE =
            "db/changelog-fixtures/db.changelog-fixtures.xml";
    private static final String TEMPLATE_DATABASE = "dml_utils_template";
    private static final String OWNER_USER = "dml_utils_owner";
    private static final String OWNER_PASSWORD = "dml_utils_owner";
    private static final String TEST_USER = "dml_utils_test";
    private static final String TEST_PASSWORD = "dml_utils_test";
    /**
     * The PostgreSQL image to run, matching the one used for jOOQ codegen.
     * Set by surefire from the {@code postgres.image} Maven property. The
     * custom image has the application roles baked in.
     */
    private static final String POSTGRES_IMAGE =
            System.getProperty("postgres.image", "dml-utils-postgres:17-alpine");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE)
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("dml_utils");

    static {
        try {
            POSTGRES.start();
            prepareTemplateDatabase();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start the PostgreSQL test container", e);
        }
    }

    /**
     * jOOQ context connected to this test class's private database.
     */
    protected DSLContext dsl;
    private String databaseName;
    private Connection connection;

    private static void prepareTemplateDatabase() {
        try {
            try (Connection admin = openConnection(POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                // Tolerate a template left behind by an interrupted earlier run
                // in the same container, so setup is repeatable.
                statement.execute("DROP DATABASE IF EXISTS " + TEMPLATE_DATABASE + " WITH (FORCE)");
                statement.execute("CREATE DATABASE " + TEMPLATE_DATABASE);
            }
            // The template database is fresh, so grant the owner role the privileges it
            // needs to run Liquibase (as the init script does for the main
            // database): CREATE on the database and on its public schema.
            try (Connection admin = openConnection(POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("GRANT CREATE ON DATABASE " + TEMPLATE_DATABASE + " TO " + OWNER_USER);
            }
            try (Connection admin = openConnection(TEMPLATE_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("GRANT CREATE ON SCHEMA public TO " + OWNER_USER);
                // The test role acts as an application caller; let it create
                // tables it owns so SECURITY INVOKER routines that require
                // ownership can be exercised.
                statement.execute("GRANT CREATE ON SCHEMA public TO " + TEST_USER);
            }
            // Install the extensions compiled into the custom image before the
            // changelog runs: plpgsql_check for static analysis and pg_background
            // for the chunking routines. The chunking routines declare
            // pg_background types, so the extension must exist when Liquibase
            // creates them.
            try (Connection admin = openConnection(TEMPLATE_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS plpgsql_check");
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_background");
                // pg_background grants no access to PUBLIC; grant its role to
                // the caller and test roles (membership is cluster-wide).
                statement.execute("GRANT pgbackground_role TO " + OWNER_USER);
                statement.execute("GRANT pgbackground_role TO " + TEST_USER);
            }
            try (Connection connection = openConnection(TEMPLATE_DATABASE, OWNER_USER, OWNER_PASSWORD)) {
                // Liquibase keeps its tracking tables in a dedicated schema and
                // does not create the schema itself, so create it as the owner
                // (who owns the tables) before the update.
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + LIQUIBASE_SCHEMA);
                }
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
                database.setDatabaseChangeLogTableName(DATABASE_CHANGELOG_TABLE);
                database.setDatabaseChangeLogLockTableName(DATABASE_CHANGELOG_LOCK_TABLE);
                Liquibase liquibase = new Liquibase(
                        ChangelogTestSupport.MASTER_RESOURCE,
                        new ClassLoaderResourceAccessor(),
                        database);
                liquibase.update();
                // The test-only fixtures are a separate changelog (not part of
                // the production master), applied to the same database.
                Liquibase fixtures = new Liquibase(
                        FIXTURES_RESOURCE,
                        new ClassLoaderResourceAccessor(),
                        database);
                fixtures.update();
            }
            // Mark as a real template so nothing can connect to it, which
            // keeps CREATE DATABASE ... TEMPLATE always safe.
            try (Connection admin = openConnection(POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                //noinspection Annotator
                statement.execute("ALTER DATABASE " + TEMPLATE_DATABASE + " WITH IS_TEMPLATE TRUE");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare template database", e);
        }
    }

    private static Connection openConnection(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                        + POSTGRES.getMappedPort(5432) + "/" + database,
                user, password);
    }

    /**
     * Returns the SQLSTATE of the first {@link SQLException} in a throwable's
     * cause chain, or {@code null} when there is none.
     *
     * @param throwable the throwable to inspect
     * @return the SQLSTATE, or {@code null}
     */
    protected static String sqlState(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /**
     * Asserts that a call fails with the given SQLSTATE.
     *
     * @param expectedSqlState the expected SQLSTATE
     * @param call             the call under test
     */
    protected static void assertSqlState(String expectedSqlState, Executable call) {
        DataAccessException exception = assertThrows(DataAccessException.class, call);
        assertEquals(expectedSqlState, sqlState(exception),
                () -> "expected SQLSTATE " + expectedSqlState + " but was: " + exception.getMessage());
    }

    /**
     * Asserts that a call fails with SQLSTATE {@code 23514}
     * ({@code check_violation}), as a domain constraint violation does.
     *
     * @param call the call under test
     */
    protected static void assertDomainViolation(Executable call) {
        assertSqlState("23514", call);
    }

    /**
     * Returns a jOOQ field reference by name, for fixture columns when no
     * generated field is available.
     *
     * @param name the column name
     * @param type the column's Java type
     * @param <T>  the column type
     * @return the field reference
     */
    protected static <T> Field<T> field(String name, Class<T> type) {
        return DSL.field(name, type);
    }

    /**
     * Evaluates a SQL expression and returns its single value as the given
     * Java type.
     *
     * <p>This is plain SQL on purpose: testing a domain's check constraint
     * means casting a literal to the domain, and jOOQ renders a cast to a
     * {@code Domain}'s data type as the base type, so the constraint would not
     * be exercised.
     *
     * @param expression the SQL expression to evaluate
     * @param type       the Java type to read the value as
     * @param <T>        the value type
     * @return the expression's value
     */
    protected <T> T evaluate(String expression, Class<T> type) {
        Record record = dsl.fetchOne("SELECT " + expression);
        return record.get(0, type);
    }

    /**
     * Creates this test class's private database from the migrated template
     * and opens a jOOQ context to it as the {@code dml_utils_test} role.
     */
    @BeforeAll
    void createTestDatabase() throws Exception {
        databaseName = "test_" + getClass().getSimpleName().toLowerCase()
                + "_" + Integer.toHexString(getClass().getName().hashCode());
        try (Connection admin = openConnection(POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " TEMPLATE " + TEMPLATE_DATABASE);
        }
        connection = openConnection(databaseName, TEST_USER, TEST_PASSWORD);
        dsl = DSL.using(connection, SQLDialect.POSTGRES);
    }

    /**
     * Opens an additional connection to this test class's private database as
     * the test role, for tests that need a second session. The caller is
     * responsible for closing it.
     *
     * @return a new connection to the private test database
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openTestConnection() throws SQLException {
        return openConnection(databaseName, TEST_USER, TEST_PASSWORD);
    }

    /**
     * Opens a connection to this test class's private database as the schema
     * owner, for tests that must change data the caller role cannot. The
     * caller is responsible for closing it.
     *
     * @return a new owner connection to the private test database
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openOwnerConnection() throws SQLException {
        return openConnection(databaseName, OWNER_USER, OWNER_PASSWORD);
    }

    /**
     * Returns whether a relation exists, reading the catalog directly so the
     * lookup works for schemas the test role has no privileges on.
     *
     * @param schema   the schema name
     * @param relation the relation name
     * @return {@code true} when the relation exists
     */
    protected boolean relationExists(String schema, String relation) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_class c
                            JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = ? AND c.relname = ?
                        )
                        """,
                schema, relation));
    }

    /**
     * Returns whether a table exists.
     *
     * @param schema the table schema
     * @param table  the table name
     * @return {@code true} when the table exists
     */
    protected boolean tableExists(String schema, String table) {
        return relationExists(schema, table);
    }

    /**
     * Returns whether a table exists in the public schema.
     *
     * @param table the table name
     * @return {@code true} when the table exists
     */
    protected boolean tableExists(String table) {
        return tableExists(PUBLIC_SCHEMA, table);
    }

    /**
     * Returns the {@code information_schema.columns} row for a column, or
     * {@code null} when the column does not exist.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the column's information_schema row, or {@code null}
     */
    protected Record column(String schema, String table, String column) {
        return dsl.fetchOne(
                """
                        SELECT *
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = ? AND column_name = ?
                        """,
                schema, table, column);
    }

    /**
     * Returns whether a column exists.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return {@code true} when the column exists
     */
    protected boolean hasColumn(String schema, String table, String column) {
        return column(schema, table, column) != null;
    }

    /**
     * Returns a single {@code information_schema.columns} attribute for a
     * column, failing when the column does not exist.
     *
     * @param schema    the table schema
     * @param table     the table name
     * @param column    the column name
     * @param attribute the information_schema column to read
     * @return the attribute value
     */
    protected String columnAttribute(String schema, String table, String column, String attribute) {
        Record record = column(schema, table, column);
        assertNotNull(record, () -> "column not found: " + schema + "." + table + "." + column);
        return record.get(attribute, String.class);
    }

    /**
     * Returns whether the named trigger exists on a table.
     *
     * @param schema  the table schema
     * @param table   the table name
     * @param trigger the trigger name
     * @return {@code true} when the trigger exists
     */
    protected boolean triggerExists(String schema, String table, String trigger) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_trigger t
                            JOIN pg_class c ON c.oid = t.tgrelid
                            JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = ? AND c.relname = ? AND t.tgname = ? AND NOT t.tgisinternal
                        )
                        """,
                schema, table, trigger));
    }

    /**
     * Closes the connection and drops this test class's private database.
     */
    @AfterAll
    void dropTestDatabase() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (databaseName != null) {
            try (Connection admin = openConnection(POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName + " WITH (FORCE)");
            }
        }
    }

    /**
     * Returns whether the test class's private database name was assigned;
     * used by tests that need to assert setup ran.
     *
     * @return {@code true} when a database is open
     */
    protected boolean databaseReady() {
        return Objects.nonNull(connection);
    }
}
