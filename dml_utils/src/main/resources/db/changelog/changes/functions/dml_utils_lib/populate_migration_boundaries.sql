CREATE OR REPLACE FUNCTION dml_utils_lib.populate_migration_boundaries(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_sql_text dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer DEFAULT 1
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns name[];
    l_key_kinds           text[];
    l_run_id              bigint;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_primary_key_columns := dml_utils_lib.primary_key_columns(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_key_kinds := dml_utils_lib.primary_key_kinds(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    PERFORM dml_utils_lib.assert_no_active_run_for_label(i_label => i_label);

    INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size, threads,
                                              driving_table_schema_name, driving_table_name)
    VALUES (i_label, i_sql_text, i_chunk_size, i_threads, i_schema_name, i_table_name)
    RETURNING run_id
        INTO l_run_id;

    -- The boundary statement is built by
    -- dml_utils_lib.build_boundary_population_sql, shared with the explain path
    -- so both execute and explain the identical SQL. It is safe because every
    -- identifier comes from the catalog and every kind from the
    -- primary_key_kinds whitelist; the run id and chunk size are inlined as
    -- bigints. Boundaries are written as the contiguous sequence 0..N in one
    -- statement, so a run has exactly the chunk starts plus one terminal
    -- high-water boundary the runner assumes; this is the only supported write
    -- path for boundaries.
    EXECUTE dml_utils_lib.build_boundary_population_sql(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_primary_key_columns => l_primary_key_columns,
            i_key_kinds => l_key_kinds,
            i_run_id => l_run_id,
            i_chunk_size => i_chunk_size::bigint);

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_migration_boundaries IS
    'Creates a migration run for the label and populates its fixed-row chunk '
        'boundaries for the given table, returning the new run_id. Boundaries are '
        'written as the contiguous sequence 0..N in one statement, so a run has '
        'exactly the chunk starts plus one terminal high-water boundary the runner '
        'assumes; this is the only supported write path for boundaries.';
