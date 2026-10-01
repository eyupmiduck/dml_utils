-- Rollback of the caller grants: revoke what the forward changeset granted and
-- restore PostgreSQL's defaults.
GRANT EXECUTE ON FUNCTION dml_utils.set_updated_at() TO public;
GRANT EXECUTE ON FUNCTION dml_utils.reject_migration_run_update() TO public;
GRANT EXECUTE ON FUNCTION dml_utils.reject_migration_boundary_update() TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.populate_migration_boundaries(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.populate_migration_boundaries(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.archive_migration_run(
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.set_migration_run_sql_text(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.process_migration_chunk(
    bigint,
    bigint,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.process_migration_chunk(
    bigint,
    bigint,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.run_migration_chunks(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.positive_integer,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils.record_migration_error(
    bigint,
    bigint,
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils.record_migration_error(
    bigint,
    bigint,
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_no_active_run_for_label(
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_chunking_template(
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    name,
    bigint,
    bigint,
    boolean
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.render_chunk_sql(
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    dml_utils.non_null_text,
    name,
    bigint,
    bigint,
    boolean
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_bigint_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_bigint_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_supported_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_supported_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.single_column_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.single_column_primary_key(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_table_exists(
    dml_utils.non_null_text,
    dml_utils.non_null_text
    ) TO public;
REVOKE EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils.non_null_text
    ) FROM dml_utils_caller;
GRANT EXECUTE ON FUNCTION dml_utils_lib.assert_schema_exists(
    dml_utils.non_null_text
    ) TO public;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils.migration_boundary FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils.migration_error FROM dml_utils_caller;
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils.migration_run FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.positive_integer FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_null_text FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils_lib FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils FROM dml_utils_caller;
