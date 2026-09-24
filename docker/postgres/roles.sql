-- Database initialization: create the application roles and grants.
-- Runs as the container superuser on first init, before Liquibase.
-- Roles are cluster-wide, so this runs once per container, not per database.

-- Abort on the first error so a failed \connect cannot silently apply the
-- following grants to the wrong database.
\set ON_ERROR_STOP on

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_owner') THEN
            CREATE ROLE dml_utils_owner LOGIN PASSWORD 'dml_utils_owner';
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_caller') THEN
            CREATE ROLE dml_utils_caller;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_test') THEN
            CREATE ROLE dml_utils_test LOGIN PASSWORD 'dml_utils_test';
        END IF;
    END
$$;

-- The owner role creates the dml_utils schema and the Liquibase tracking
-- tables, so it needs CREATE on the application database and on the public
-- schema of that database. The schema grant is per-database, so connect to
-- the application database first.
GRANT CREATE ON DATABASE dml_utils TO dml_utils_owner;

\connect dml_utils
GRANT CREATE ON SCHEMA public TO dml_utils_owner;

-- Liquibase stores its tracking tables (dml_utils_databasechangelog and
-- dml_utils_databasechangeloglock) in a dedicated schema. Liquibase does not
-- create the schema itself, so create it here, owned by the role that runs the
-- migration and creates the tables.
CREATE SCHEMA IF NOT EXISTS liquibase AUTHORIZATION dml_utils_owner;

-- plpgsql_check is compiled into this image and used for static analysis of
-- the dml_utils / dml_utils_lib routines (for example
-- SELECT plpgsql_check_function('dml_utils.set_updated_at()'::regprocedure)).
-- This only creates it in the dml_utils database, and init scripts only run on
-- first cluster initialization: an existing dev volume needs a manual
-- `CREATE EXTENSION plpgsql_check;` (or scripts/refresh-local-db.sh).
CREATE EXTENSION IF NOT EXISTS plpgsql_check;

-- The test role exercises the same privileges as a real application caller.
GRANT dml_utils_caller TO dml_utils_test;
