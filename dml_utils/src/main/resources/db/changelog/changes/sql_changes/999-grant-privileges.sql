-- Caller grants for the dml_utils objects. The changeset is runOnChange, so
-- keep the statements idempotent.
GRANT USAGE ON SCHEMA dml_utils TO dml_utils_caller;
GRANT USAGE ON SCHEMA dml_utils_lib TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_negative_integer TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_null_text TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_null_boolean TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_text_array TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_non_null_text_array TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_non_null_boolean_array TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.positive_integer TO dml_utils_caller;

REVOKE ALL ON dml_utils.migration_run FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils.migration_run TO dml_utils_caller;
REVOKE ALL ON dml_utils.migration_boundary FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils.migration_boundary TO dml_utils_caller;

-- Trigger functions are invoked by the trigger machinery, not by callers, so
-- revoke PUBLIC EXECUTE and grant it to no one.
REVOKE EXECUTE ON FUNCTION dml_utils.set_updated_at() FROM public;

-- Functions grant EXECUTE to PUBLIC by default; revoke it and grant only to
-- the caller role, so execution is explicit.
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.single_column_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.single_column_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_bigint_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_bigint_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils.non_null_text
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    name,
    bigint,
    bigint,
    boolean
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    name,
    bigint,
    bigint,
    boolean
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.populate_migration_boundaries(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.populate_migration_boundaries(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer
    ) TO dml_utils_caller;
REVOKE EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils.non_null_text
    ) TO dml_utils_caller;
