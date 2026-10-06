CREATE OR REPLACE FUNCTION dml_utils.explain_function_over_table(
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000
)
    RETURNS TABLE
            (
                o_plan_kind text,
                o_sql_text  text,
                o_plan      json
            )
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_function_chunk_template text;
    l_primary_key_columns     name[];
    l_key_kinds               text[];
BEGIN
    -- Validate the function against the driving table's primary key and build
    -- the per-row template first, exactly as the runner does; a bad table or
    -- function fails before any plan is produced.
    l_function_chunk_template := dml_utils_lib.build_function_chunk_template(
            i_table_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name,
            i_function_schema_name => i_function_schema_name,
            i_function_name => i_function_name);

    l_primary_key_columns := dml_utils_lib.primary_key_columns(
            i_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name);
    l_key_kinds := dml_utils_lib.primary_key_kinds(
            i_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name);

    -- The generated template is a valid <driving_table>/<chunking_clause>
    -- template whose alias is fixed at 't', so the shared explain path renders
    -- and plans it unchanged.
    RETURN QUERY
        SELECT p.o_plan_kind, p.o_sql_text, p.o_plan
        FROM dml_utils_lib.explain_chunk_plans(
                     i_sql_text => l_function_chunk_template,
                     i_schema_name => i_driving_table_schema_name,
                     i_table_name => i_driving_table_name,
                     i_table_alias => 't',
                     i_primary_key_columns => l_primary_key_columns,
                     i_key_kinds => l_key_kinds,
                     i_chunk_size => i_chunk_size::bigint) AS p;
END;
$$;

COMMENT ON FUNCTION dml_utils.explain_function_over_table IS
    'Returns the EXPLAIN (FORMAT JSON) plans for the SQL a '
        'run_function_over_table call would generate: the boundary-population '
        'insert, a non-final chunk and the final chunk, as rows '
        'boundary_population, chunk_non_final and chunk_final. The function must '
        'return void with argument types matching the driving table''s primary '
        'key, in key order. It resolves no run, reads no boundaries and writes '
        'nothing; the chunk ranges are synthetic, so their estimates may differ '
        'from a real chunk.';
