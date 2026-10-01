CREATE OR REPLACE FUNCTION dml_utils_lib.render_chunk_sql(
    i_sql_text          dml_utils.non_null_text,
    i_schema_name       dml_utils.non_null_text,
    i_table_name        dml_utils.non_null_text,
    i_table_alias       dml_utils.non_null_text,
    i_primary_key_name  name,
    i_start_id          bigint,
    i_end_id            bigint,
    i_is_final          boolean
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_driving_table   text;
    l_chunking_clause text;
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

    -- Both tokens are validated to occur exactly once; the replacements are
    -- quoted, so a substituted value cannot reintroduce either token.
    RETURN pg_catalog.replace(
        pg_catalog.replace(i_sql_text, '<driving_table>', l_driving_table),
        '<chunking_clause>', l_chunking_clause);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.render_chunk_sql IS
    'Returns the SQL template with <driving_table> and <chunking_clause> '
        'substituted for the given table, alias, primary key and chunk range; '
        'the final chunk uses an inclusive upper bound.';
