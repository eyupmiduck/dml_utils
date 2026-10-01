CREATE OR REPLACE FUNCTION dml_utils_lib.render_chunk_sql(
    i_sql_text dml_utils.non_null_text,
    i_schema_name dml_utils.non_null_text,
    i_table_name dml_utils.non_null_text,
    i_table_alias dml_utils.non_null_text,
    i_primary_key_name name,
    i_start_id bigint,
    i_end_id bigint,
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
    l_chunking_clause                   text;
    l_rendered                          text;
BEGIN
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- The driving table is referenced as "<schema>.<table> <alias>" so the
    -- template's column references can use the alias.
    l_driving_table := pg_catalog.format('%I.%I %I',
                                         i_schema_name, i_table_name, i_table_alias);

    -- The range predicate is parenthesized so it drops into a template clause
    -- verbatim. The final chunk uses an inclusive upper bound so the captured
    -- maximum row is processed; every other chunk is half-open. The ids are
    -- typed bigint, so %s emits them as bare integer literals safely.
    l_chunking_clause := pg_catalog.format(
            '(%I.%I >= %s AND %I.%I %s %s)',
            i_table_alias, i_primary_key_name, i_start_id,
            i_table_alias, i_primary_key_name,
            CASE WHEN i_is_final THEN '<=' ELSE '<' END,
            i_end_id);

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
        'substituted for the given table, alias, primary key and chunk range; '
        'the final chunk uses an inclusive upper bound.';
