-- Caller grants for the dml_utils objects. The changeset is runOnChange, so
-- keep the statements idempotent.
GRANT USAGE ON SCHEMA dml_utils TO dml_utils_caller;
GRANT USAGE ON SCHEMA dml_utils_lib TO dml_utils_caller;
GRANT USAGE ON SCHEMA dml_utils_data TO dml_utils_caller;

-- PostgreSQL grants USAGE on types and domains to PUBLIC by default. Revoke
-- that and grant it only to the caller, so the application's types follow the
-- same least-privilege rule as its tables and routines.
REVOKE USAGE ON DOMAIN dml_utils_data.non_null_text FROM public;
GRANT USAGE ON DOMAIN dml_utils_data.non_null_text TO dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils_data.positive_integer FROM public;
GRANT USAGE ON DOMAIN dml_utils_data.positive_integer TO dml_utils_caller;
REVOKE USAGE ON TYPE dml_utils_data.migration_key FROM public;
GRANT USAGE ON TYPE dml_utils_data.migration_key TO dml_utils_caller;
REVOKE USAGE ON TYPE dml_utils_data.chunking_strategy FROM public;
GRANT USAGE ON TYPE dml_utils_data.chunking_strategy TO dml_utils_caller;

-- Same rule for objects created later: PostgreSQL grants EXECUTE on functions
-- and USAGE on types to PUBLIC by default, so revoke those defaults for the
-- owner. Every future routine/type then needs an explicit grant, like the ones
-- above, instead of silently becoming world-executable.
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils
    REVOKE EXECUTE ON FUNCTIONS FROM public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_lib
    REVOKE EXECUTE ON FUNCTIONS FROM public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_data
    REVOKE EXECUTE ON FUNCTIONS FROM public;
ALTER DEFAULT PRIVILEGES FOR ROLE dml_utils_owner IN SCHEMA dml_utils_data
    REVOKE USAGE ON TYPES FROM public;

REVOKE ALL ON dml_utils_data.migration_run FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_run TO dml_utils_caller;
REVOKE ALL ON dml_utils_data.migration_boundary FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_boundary TO dml_utils_caller;
REVOKE ALL ON dml_utils_data.migration_error FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils_data.migration_error TO dml_utils_caller;

-- Trigger functions are invoked by the trigger machinery, not by callers, so
-- revoke PUBLIC EXECUTE and grant it to no one.
REVOKE EXECUTE ON FUNCTION dml_utils_data.set_updated_at() FROM public;
REVOKE EXECUTE ON FUNCTION dml_utils_data.reject_migration_run_update() FROM public;
REVOKE EXECUTE ON FUNCTION dml_utils_data.reject_migration_boundary_update() FROM public;

-- Functions grant EXECUTE to PUBLIC by default; revoke it and grant only to
-- the caller role, so execution is explicit.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_plain_heap(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_plain_heap(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_columns(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.primary_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.primary_key_kinds(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_values(
    dml_utils_data.migration_key,
    text[]
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_values(
    dml_utils_data.migration_key,
    text[]
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(
    dml_utils_data.migration_key
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.migration_key_is_canonical(
    dml_utils_data.migration_key
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_function_chunk_template(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_function_chunk_template(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.resolve_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    text[],
    text[],
    boolean
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.populate_migration_boundaries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.populate_migration_boundaries(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    bigint,
    bigint,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.process_migration_chunk(
    bigint,
    bigint,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    bigint,
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.record_migration_error(
    bigint,
    bigint,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    bigint
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.build_block_boundary_population_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_block_chunk_sql(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    bigint,
    bigint
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_relation_filepath(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.synthetic_chunk_boundary_values(
    text[],
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.synthetic_chunk_boundary_values(
    text[],
    bigint
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(
    text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_query_plan(
    text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.explain_chunk_plans(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    name[],
    text[],
    bigint,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;

REVOKE EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.run_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    text,
    text,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.run_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    text,
    text,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.explain_migration_chunks(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.explain_function_over_table(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    text,
    dml_utils_data.chunking_strategy
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_function(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_function(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_threads(
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_threads(
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs() FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs() TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.delete_archived_migration_runs(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_errors(
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.migration_errors(
    bigint
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.migration_boundaries(
    bigint
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.migration_boundaries(
    bigint
    ) TO dml_utils_caller;
