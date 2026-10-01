CREATE OR REPLACE FUNCTION dml_utils_lib.primary_key_kind(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name name;
    l_primary_key_type pg_catalog.regtype;
BEGIN
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    SELECT a.atttypid::pg_catalog.regtype
    INTO STRICT l_primary_key_type
    FROM pg_catalog.pg_class AS c
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
             JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
    WHERE n.nspname = i_schema_name
      AND c.relname = i_table_name
      AND a.attname = l_primary_key_name;

    -- The fixed-row chunk model needs a single ordered key. The integer types
    -- all pack into the bigint attribute of migration_key.
    IF l_primary_key_type IN ('smallint'::pg_catalog.regtype,
                              'integer'::pg_catalog.regtype,
                              'bigint'::pg_catalog.regtype)
    THEN
        RETURN 'bigint';
    END IF;

    IF l_primary_key_type = 'text'::pg_catalog.regtype THEN
        RETURN 'text';
    END IF;

    IF l_primary_key_type = 'uuid'::pg_catalog.regtype THEN
        RETURN 'uuid';
    END IF;

    RAISE EXCEPTION 'primary key column %.%.% has unsupported type %',
        i_schema_name, i_table_name, l_primary_key_name, l_primary_key_type
        USING ERRCODE = '22023';
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_kind IS
    'Returns the boundary key kind (bigint, text or uuid) of the table''s single '
        'primary-key column, raising invalid_parameter_value (22023) for an '
        'unsupported type.';
