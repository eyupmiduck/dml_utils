CREATE OR REPLACE FUNCTION dml_utils.set_migration_run_function(
    i_label dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_function_chunk_template text;
BEGIN
    -- The run's driving table (immutable) decides which primary-key signature the
    -- function must match. Read it from the stored run and build the template in
    -- one statement, so the domain-typed columns are passed straight to the
    -- non_null_text arguments with no hidden domain-to-text cast into a local.
    -- With no matching run the function is never evaluated and NOT FOUND below
    -- raises P0002.
    SELECT dml_utils_lib.build_function_chunk_template(
                   i_table_schema_name => driving_table_schema_name,
                   i_table_name => driving_table_name,
                   i_function_schema_name => i_function_schema_name,
                   i_function_name => i_function_name)
    INTO l_function_chunk_template
    FROM dml_utils_data.migration_run
    WHERE label = i_label
      AND archived_at IS NULL
      AND completed_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no unfinished migration run for label %', i_label
            USING ERRCODE = 'P0002';
    END IF;

    -- Reuse the base mutator: it validates the template and only updates an
    -- unfinished, unarchived run.
    PERFORM dml_utils.set_migration_run_sql_text(
            i_label => i_label,
            i_sql_text => l_function_chunk_template);
END;
$$;

COMMENT ON FUNCTION dml_utils.set_migration_run_function IS
    'Replaces the recorded sql_text of the unfinished run for the label with the '
        'template that calls the given function over the run''s driving table, so '
        'the next run_function_over_table call uses the adjusted function.';
