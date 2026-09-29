CREATE OR REPLACE FUNCTION dml_utils_lib.single_column_primary_key(
    i_schema_name dml_utils.non_null_text,
    i_table_name dml_utils.non_null_text
)
    RETURNS name
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_column_count smallint;
    l_primary_key_name name;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    SELECT i.indnkeyatts
    INTO l_key_column_count
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE i.indisprimary
      AND n.nspname = i_schema_name
      AND c.relname = i_table_name;

    IF l_key_column_count IS NULL THEN
        RAISE EXCEPTION 'table %.% has no primary key', i_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;

    IF l_key_column_count <> 1 THEN
        RAISE EXCEPTION 'table %.% has a composite primary key',
            i_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;

    SELECT a.attname
    INTO STRICT l_primary_key_name
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
             JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]
    WHERE i.indisprimary
      AND n.nspname = i_schema_name
      AND c.relname = i_table_name;

    RETURN l_primary_key_name;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.single_column_primary_key IS
    'Returns the single primary-key column, raising invalid_parameter_value '
        '(22023) when the table has no primary key or a composite primary key.';
