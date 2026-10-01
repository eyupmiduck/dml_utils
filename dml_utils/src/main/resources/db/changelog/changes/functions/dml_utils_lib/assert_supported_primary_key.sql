CREATE OR REPLACE FUNCTION dml_utils_lib.assert_supported_primary_key(
    i_schema_name dml_utils.non_null_text,
    i_table_name  dml_utils.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name name;
BEGIN
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    -- The fixed-row chunk model needs a single ordered integer key. Accept the
    -- integer types; reject anything else (text, numeric, uuid, ...).
    IF NOT EXISTS (SELECT 1
                   FROM pg_catalog.pg_class AS c
                            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
                            JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
                   WHERE n.nspname = i_schema_name
                     AND c.relname = i_table_name
                     AND a.attname = l_primary_key_name
                     AND a.atttypid IN ('smallint'::pg_catalog.regtype,
                                        'integer'::pg_catalog.regtype,
                                        'bigint'::pg_catalog.regtype))
    THEN
        RAISE EXCEPTION 'primary key column %.%.% is not an integer type',
            i_schema_name, i_table_name, l_primary_key_name
            USING ERRCODE = '22023';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_supported_primary_key IS
    'Raises invalid_parameter_value (22023) when the single primary-key column '
        'is not smallint, integer or bigint.';
