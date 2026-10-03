CREATE OR REPLACE FUNCTION dml_utils_lib.build_function_chunk_template(
    i_table_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_function_schema_name dml_utils_data.non_null_text,
    i_function_name dml_utils_data.non_null_text
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns name[];
    l_call_arguments      text;
    l_argument_oids       oid[];
    l_function_oids       oid[];
    l_function_not_void   boolean;
    l_argument_type_list  text;
BEGIN
    -- Resolve the driving table's primary key in key order; this also validates
    -- the schema/table exist and that the key has one to three supported columns.
    l_primary_key_columns := dml_utils_lib.primary_key_columns(
            i_schema_name => i_table_schema_name,
            i_table_name => i_table_name);

    -- The function's argument types must equal the primary-key column types, in
    -- key order, and it must return void. Read the column types straight from
    -- the catalog (atttypid), because the engine's key *kinds* collapse the
    -- integer family to bigint while a function's declared argument type must
    -- match the column's actual type.
    SELECT pg_catalog.array_agg(a.atttypid ORDER BY k.ordinality)
    INTO l_argument_oids
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
             JOIN pg_catalog.unnest(i.indkey) WITH ORDINALITY AS k(attnum, ordinality)
                  ON true
             JOIN pg_catalog.pg_attribute AS a
                  ON a.attrelid = c.oid AND a.attnum = k.attnum
    WHERE i.indisprimary
      AND n.nspname = i_table_schema_name
      AND c.relname = i_table_name
      AND k.ordinality <= i.indnkeyatts;

    IF pg_catalog.cardinality(l_argument_oids)
        IS DISTINCT FROM pg_catalog.array_length(l_primary_key_columns, 1)
    THEN
        RAISE EXCEPTION 'primary key of table %.% changed while reading its columns',
            i_table_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;

    -- Look up the function by exact name and arity. proargtypes is a 0-based
    -- oidvector of the input argument types (IN/INOUT/VARIADIC); compare it to
    -- the primary-key column types position by position.
    -- proargtypes is a 0-based oidvector; normalise it to a 1-based array so it
    -- can be compared with the array_agg-built list of column types (PostgreSQL
    -- array equality is by dimensions, so mismatched lower bounds compare
    -- unequal even when the elements match).
    SELECT (SELECT pg_catalog.array_agg(x ORDER BY o)
            FROM pg_catalog.unnest(p.proargtypes::oid[]) WITH ORDINALITY AS u(x, o)),
           p.prorettype <> 'void'::pg_catalog.regtype
    INTO l_function_oids, l_function_not_void
    FROM pg_catalog.pg_proc AS p
             JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
    WHERE n.nspname = i_function_schema_name
      AND p.proname = i_function_name
      AND p.pronargs = pg_catalog.cardinality(l_argument_oids);

    IF NOT FOUND THEN
        SELECT pg_catalog.string_agg(t::pg_catalog.regtype::text, ', ' ORDER BY o)
        INTO l_argument_type_list
        FROM pg_catalog.unnest(l_argument_oids) WITH ORDINALITY AS a(t, o);

        RAISE EXCEPTION 'function %.%(%) does not exist in schema %',
            i_function_schema_name, i_function_name, l_argument_type_list, i_function_schema_name
            USING ERRCODE = '22023';
    END IF;

    IF l_function_not_void THEN
        RAISE EXCEPTION 'function %.% must return void',
            i_function_schema_name, i_function_name
            USING ERRCODE = '22023';
    END IF;

    IF l_function_oids IS DISTINCT FROM l_argument_oids THEN
        RAISE EXCEPTION 'function %.% arguments do not match the primary-key column types of %.% in key order',
            i_function_schema_name, i_function_name, i_table_schema_name, i_table_name
            USING ERRCODE = '22023';
    END IF;

    -- Build the call argument list and the function reference. Identifiers are
    -- catalog names, quoted with %I; the table alias is fixed at the engine's
    -- default 't'.
    l_call_arguments := '';
    FOR l_position IN 1..pg_catalog.array_length(l_primary_key_columns, 1)
        LOOP
            l_call_arguments := l_call_arguments || pg_catalog.format('%st.%I',
                                                                      CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                      l_primary_key_columns[l_position]);
        END LOOP;

    -- The template calls the function once per row of the chunk's key range,
    -- passing the primary-key columns as arguments. <driving_table> and
    -- <chunking_clause> are filled in per chunk by render_chunk_sql.
    RETURN pg_catalog.format(
            'SELECT %I.%I(%s) FROM <driving_table> WHERE <chunking_clause>',
            i_function_schema_name,
            i_function_name,
            l_call_arguments);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.build_function_chunk_template IS
    'Builds the <driving_table>/<chunking_clause> template that calls the given '
        'function once per row, passing the driving table''s primary-key columns; '
        'validates the function exists and returns void with argument types '
        'matching the primary-key column types in key order.';
