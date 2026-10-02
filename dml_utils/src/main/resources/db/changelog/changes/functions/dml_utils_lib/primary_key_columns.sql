CREATE OR REPLACE FUNCTION dml_utils_lib.primary_key_columns(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS name[]
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_column_count smallint;
    l_primary_key_columns name[];
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

    IF l_key_column_count > 3 THEN
        RAISE EXCEPTION 'table %.% has a % column primary key; at most 3 are supported',
            i_schema_name, i_table_name, l_key_column_count
            USING ERRCODE = '22023';
    END IF;

    -- indkey lists the key columns in key order; unnest WITH ORDINALITY keeps
    -- that order (attnum order would not, for example a primary key declared as
    -- (b, a)).
    SELECT pg_catalog.array_agg(a.attname ORDER BY k.ordinality)
    INTO l_primary_key_columns
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
             JOIN pg_catalog.unnest(i.indkey) WITH ORDINALITY AS k(attnum, ordinality)
                  ON true
             JOIN pg_catalog.pg_attribute AS a
                  ON a.attrelid = c.oid AND a.attnum = k.attnum
    WHERE i.indisprimary
      AND n.nspname = i_schema_name
      AND c.relname = i_table_name
      AND k.ordinality <= i.indnkeyatts;

    RETURN l_primary_key_columns;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_columns IS
    'Returns the primary-key columns in key order, raising '
        'invalid_parameter_value (22023) when the table has no primary key or more '
        'than 3 key columns.';
