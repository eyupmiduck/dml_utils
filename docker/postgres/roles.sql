-- Database initialization: create the application roles and grants.
-- Runs as the container superuser on first init, before Liquibase.
-- Roles are cluster-wide, so this runs once per container, not per database.
--
-- These roles and passwords are for a throwaway local/dev cluster only; a real
-- deployment provisions its own credentials (see README's install section).
--
-- This script runs only on first initialization: an existing dev volume keeps
-- whatever roles it already has. It therefore reconciles (not just creates) the
-- login roles, so a pre-existing role with a different login state or password
-- cannot silently satisfy the existence check while leaving the cluster
-- unusable.

-- Abort on the first error so a failed \connect cannot silently apply the
-- following grants to the wrong database.
\set ON_ERROR_STOP on

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_owner') THEN
            CREATE ROLE dml_utils_owner LOGIN PASSWORD 'dml_utils_owner';
        ELSE
            ALTER ROLE dml_utils_owner LOGIN PASSWORD 'dml_utils_owner';
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_caller') THEN
            CREATE ROLE dml_utils_caller;
        ELSE
            ALTER ROLE dml_utils_caller NOLOGIN;
        END IF;
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'dml_utils_test') THEN
            CREATE ROLE dml_utils_test LOGIN PASSWORD 'dml_utils_test';
        ELSE
            ALTER ROLE dml_utils_test LOGIN PASSWORD 'dml_utils_test';
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
-- migration and creates the tables. If it already exists but is owned by
-- another role, reassign it so the migration can create/update its tables.
CREATE SCHEMA IF NOT EXISTS liquibase AUTHORIZATION dml_utils_owner;
-- If it already existed under another owner, reassign it (and grant a fallback
-- CREATE) so the migration can manage its tracking tables.
ALTER SCHEMA liquibase OWNER TO dml_utils_owner;
GRANT CREATE, USAGE ON SCHEMA liquibase TO dml_utils_owner;

-- plpgsql_check is compiled into this image and used for static analysis of
-- the dml_utils / dml_utils_lib / dml_utils_data routines (for example
-- SELECT plpgsql_check_function('dml_utils_data.set_updated_at()'::regprocedure)).
-- pg_background is compiled into this image and runs SQL in background
-- workers (autonomous transactions); the chunking routines will use it.
-- These only create the extensions in the dml_utils database, and init scripts
-- only run on first cluster initialization: an existing dev volume needs a
-- manual `CREATE EXTENSION pg_background;` (or scripts/refresh-local-db.sh).
CREATE EXTENSION IF NOT EXISTS plpgsql_check;
CREATE EXTENSION IF NOT EXISTS pg_background;

-- The test role exercises the same privileges as a real application caller.
GRANT dml_utils_caller TO dml_utils_test;

-- pg_background grants no access to PUBLIC; callers need membership in the
-- role the extension created. Grant it to the caller and test roles so the
-- chunking routines can launch background workers.
GRANT pgbackground_role TO dml_utils_caller;
GRANT pgbackground_role TO dml_utils_test;
