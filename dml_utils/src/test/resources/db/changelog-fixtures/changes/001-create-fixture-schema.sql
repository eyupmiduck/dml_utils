-- The template database is created fresh on every test run, so this normally
-- creates the schema once; IF NOT EXISTS lets the changelog be re-applied to a
-- database whose schema survived a lost Liquibase history.
CREATE SCHEMA IF NOT EXISTS dml_utils_fixtures;

COMMENT ON SCHEMA dml_utils_fixtures IS
    'Test-only fixtures: tables the integration tests drive the migration routines over.';
