CREATE OR REPLACE FUNCTION dml_utils_lib.render_chunk_sql(
    i_sql_text dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_primary_key_columns name[],
    i_key_kinds text[],
    i_start_values text[],
    i_end_values text[],
    i_is_final boolean
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    -- Control-character sentinels that stand in for one token each while the
    -- other token is being replaced, so a substituted value can never be
    -- re-scanned or rewritten.
    l_driving_table_sentinel   constant text := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text := pg_catalog.chr(2);
    l_driving_table                     text;
    l_start_tuple                       text;
    l_end_tuple                         text;
    l_column_tuple                      text;
    l_chunking_clause                   text;
    l_rendered                          text;
    l_kind                              text;
BEGIN
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- array_length of an empty array is NULL, so a NULL length here means empty
    -- (or NULL); reject it explicitly, otherwise all four arguments agree on
    -- NULL and the loop below builds an empty, malformed predicate.
    IF pg_catalog.array_length(i_primary_key_columns, 1) IS NULL
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_key_kinds, 1)
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_start_values, 1)
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_end_values, 1)
    THEN
        RAISE EXCEPTION 'primary key columns, kinds and boundary values must have the same, non-zero length'
            USING ERRCODE = '22023';
    END IF;

    -- Build the alias-qualified column tuple and the two value tuples. The kind
    -- selects the explicit cast and is interpolated into the SQL, so it must be
    -- one of the known kinds (never caller SQL); the values are quoted with %L.
    l_column_tuple := '';
    l_start_tuple := '';
    l_end_tuple := '';
    FOR l_position IN 1..pg_catalog.array_length(i_primary_key_columns, 1)
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would slip past NOT IN (NULL NOT IN (...) is NULL,
            -- not true), so guard it explicitly.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            l_column_tuple := l_column_tuple || pg_catalog.format('%s%I.%I',
                                                                  CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                  i_table_alias,
                                                                  i_primary_key_columns[l_position]);
            l_start_tuple := l_start_tuple || pg_catalog.format('%s%L::%s',
                                                                CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                i_start_values[l_position],
                                                                l_kind);
            l_end_tuple := l_end_tuple || pg_catalog.format('%s%L::%s',
                                                            CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                            i_end_values[l_position],
                                                            l_kind);
        END LOOP;

    -- The driving table is referenced as "<schema>.<table> <alias>" so the
    -- template's column references can use the alias.
    l_driving_table := pg_catalog.format('%I.%I %I',
                                         i_schema_name, i_table_name, i_table_alias);

    -- The row-value range predicate is parenthesized so it drops into a template
    -- clause verbatim. The final chunk uses an inclusive upper bound so the
    -- captured maximum row is processed; every other chunk is half-open. A
    -- one-column key degenerates to an ordinary scalar comparison.
    l_chunking_clause := pg_catalog.format(
            '((%s) >= (%s) AND (%s) %s (%s))',
            l_column_tuple, l_start_tuple, l_column_tuple,
            CASE WHEN i_is_final THEN '<=' ELSE '<' END,
            l_end_tuple);

    -- A quoted identifier could in principle contain a sentinel character;
    -- reject that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_driving_table, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_driving_table, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'identifier contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- Replace each token with its sentinel first, then expand the sentinels.
    -- The substituted values are inserted last and are never re-scanned, so a
    -- value that literally contains the other token cannot mangle the output.
    l_rendered := pg_catalog.replace(i_sql_text, '<driving_table>', l_driving_table_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, '<chunking_clause>', l_chunking_clause_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, l_driving_table_sentinel, l_driving_table);
    l_rendered := pg_catalog.replace(l_rendered, l_chunking_clause_sentinel, l_chunking_clause);

    RETURN l_rendered;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.render_chunk_sql IS
    'Returns the SQL template with <driving_table> and <chunking_clause> '
        'substituted for the given table, alias, primary-key columns and chunk '
        'range; each key kind (bigint, text or uuid) selects the explicit cast. '
        'The final chunk uses an inclusive upper bound.';
