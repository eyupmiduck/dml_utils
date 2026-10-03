CREATE OR REPLACE FUNCTION dml_utils_lib.primary_key_kinds(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS text[]
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_key_kinds text[];
    l_column    record;
BEGIN
    -- primary_key_columns validates the schema and table exist and that the
    -- primary key has at most three columns (raising otherwise); read the type
    -- of each in key order.
    PERFORM dml_utils_lib.primary_key_columns(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    FOR l_column IN
        SELECT a.atttypid::pg_catalog.regtype AS column_type, a.attname AS column_name
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
          AND k.ordinality <= i.indnkeyatts
        ORDER BY k.ordinality
        LOOP
            -- The integer types all pack into the bigint array of migration_key.
            IF l_column.column_type IN ('smallint'::pg_catalog.regtype,
                                        'integer'::pg_catalog.regtype,
                                        'bigint'::pg_catalog.regtype)
            THEN
                l_key_kinds := l_key_kinds || 'bigint'::text;
            ELSIF l_column.column_type = 'text'::pg_catalog.regtype THEN
                l_key_kinds := l_key_kinds || 'text'::text;
            ELSIF l_column.column_type = 'uuid'::pg_catalog.regtype THEN
                l_key_kinds := l_key_kinds || 'uuid'::text;
            ELSE
                RAISE EXCEPTION 'primary key column %.%.% has unsupported type %',
                    i_schema_name, i_table_name, l_column.column_name, l_column.column_type
                    USING ERRCODE = '22023';
            END IF;
        END LOOP;

    RETURN l_key_kinds;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.primary_key_kinds IS
    'Returns the boundary key kind (bigint, text or uuid) of each primary-key '
        'column in key order, raising invalid_parameter_value (22023) for an '
        'unsupported type.';
