CREATE OR REPLACE FUNCTION dml_utils_lib.assert_usable_partitioned_ordered_key(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_index_name dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
BEGIN
    -- Validation lives in partitioned_ordered_key_attributes, so the run path
    -- and the resume check cannot drift.
    PERFORM 1
    FROM dml_utils_lib.partitioned_ordered_key_attributes(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_index_name => i_index_name) AS a;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_usable_partitioned_ordered_key(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) IS
    'Raises when a partition-wise parent index or any leaf child index cannot '
        'serve as an ordered key; returns void when every leaf is eligible.';
