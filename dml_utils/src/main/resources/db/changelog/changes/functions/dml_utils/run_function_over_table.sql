CREATE OR REPLACE FUNCTION dml_utils.run_function_over_table(
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    -- Plain text, not non_null_text: an omitted label is NULL and the body
    -- derives one. non_null_text would reject the DEFAULT NULL before the body.
    i_label text DEFAULT NULL
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_function_chunk_template text;
    l_label                   text;
BEGIN
    -- Build and validate the template first: it resolves the driving table's
    -- primary key and checks the supplied function exists, returns void, and
    -- takes the primary-key column types in key order. A bad table or function
    -- fails before any run or worker exists.
    l_function_chunk_template := dml_utils_lib.build_function_chunk_template(
            i_table_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name,
            i_function_schema_name => i_function_schema_name,
            i_function_name => i_function_name);

    -- Derive a deterministic label from the driving table and function when the
    -- caller does not supply one, so re-running the same call resumes the same
    -- run instead of creating a new one. The components are joined as a JSON
    -- array, which is unambiguous even when a (quoted) name contains a '.', ':'
    -- or other delimiter: JSON escapes and quotes each element, so distinct
    -- inputs cannot collide.
    l_label := coalesce(
            i_label,
            'function:' || pg_catalog.json_build_array(
                    i_driving_table_schema_name,
                    i_driving_table_name,
                    i_function_schema_name,
                    i_function_name)::text);

    -- Delegate to the base engine: it owns resume, chunk scheduling, error
    -- recording and run completion. The generated template is a valid
    -- <driving_table>/<chunking_clause> template, so no special path is needed.
    PERFORM dml_utils.run_migration_chunks(
            i_sql_text => l_function_chunk_template,
            i_driving_table_schema_name => i_driving_table_schema_name,
            i_driving_table_name => i_driving_table_name,
            i_label => l_label,
            i_chunk_size => i_chunk_size,
            i_threads => i_threads);
END;
$$;

COMMENT ON FUNCTION dml_utils.run_function_over_table IS
    'Runs a user-supplied void function once per row of the driving table, chunk '
        'by chunk, resuming by label (derived from the table and function when '
        'i_label is NULL). The function must take the primary-key column types in '
        'key order and return void.';
