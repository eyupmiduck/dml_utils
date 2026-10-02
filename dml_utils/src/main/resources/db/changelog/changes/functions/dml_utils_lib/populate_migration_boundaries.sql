CREATE OR REPLACE FUNCTION dml_utils_lib.populate_migration_boundaries(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_sql_text dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer DEFAULT 1
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns name[];
    l_key_kinds           text[];
    l_key_select_list     text;
    l_key_id_list         text;
    l_order_by            text;
    l_order_by_desc       text;
    l_bigint_terms        text[];
    l_text_terms          text[];
    l_uuid_terms          text[];
    l_pack_expression     text;
    l_run_id              bigint;
    l_kind                text;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_primary_key_columns := dml_utils_lib.primary_key_columns(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_key_kinds := dml_utils_lib.primary_key_kinds(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    PERFORM dml_utils_lib.assert_no_active_run_for_label(i_label => i_label);

    -- Build, position by position: the SELECT list that aliases each key column
    -- as id1, id2, ..., the matching id list, the ORDER BY, and the pack
    -- expression. All identifiers are catalog names quoted with %I/quote_ident;
    -- every kind comes from the whitelist (primary_key_kinds), so interpolating
    -- the result is safe.
    l_bigint_terms := pg_catalog.array_fill('NULL'::text, ARRAY[pg_catalog.array_length(l_key_kinds, 1)]);
    l_text_terms := pg_catalog.array_fill('NULL'::text, ARRAY[pg_catalog.array_length(l_key_kinds, 1)]);
    l_uuid_terms := pg_catalog.array_fill('NULL'::text, ARRAY[pg_catalog.array_length(l_key_kinds, 1)]);
    l_key_select_list := '';
    l_key_id_list := '';
    l_order_by := '';
    l_order_by_desc := '';
    FOR l_position IN 1..pg_catalog.array_length(l_key_kinds, 1)
        LOOP
        l_kind := l_key_kinds[l_position];
        l_key_select_list := l_key_select_list || pg_catalog.format('%s%s AS id%s',
                                                                    CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                    pg_catalog.quote_ident(l_primary_key_columns[l_position]),
                                                                    l_position);
        l_key_id_list := l_key_id_list || pg_catalog.format('%sid%s',
                                                            CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                            l_position);
        l_order_by := l_order_by || pg_catalog.format('%s%s',
                                                      CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                      pg_catalog.quote_ident(l_primary_key_columns[l_position]));
        -- DESC applies per ORDER BY term, so the backward scan needs it on
        -- every column (a trailing DESC would leave the earlier columns ASC).
        l_order_by_desc := l_order_by_desc || pg_catalog.format('%s%s DESC',
                                                                CASE WHEN l_position > 1 THEN ', ' ELSE '' END,
                                                                pg_catalog.quote_ident(l_primary_key_columns[l_position]));

        IF l_kind = 'bigint' THEN
            l_bigint_terms[l_position] := pg_catalog.format('id%s::bigint', l_position);
        ELSIF l_kind = 'text' THEN
            l_text_terms[l_position] := pg_catalog.format('id%s::text', l_position);
        ELSIF l_kind = 'uuid' THEN
            l_uuid_terms[l_position] := pg_catalog.format('id%s::uuid', l_position);
        ELSE
            RAISE EXCEPTION 'unsupported key kind %', l_kind
                USING ERRCODE = '22023';
        END IF;
    END LOOP;

    l_pack_expression := pg_catalog.format(
            'ROW(%s, %s, %s)::dml_utils_data.migration_key',
            CASE
                WHEN 'bigint' = ANY (l_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::bigint[]',
                                           pg_catalog.array_to_string(l_bigint_terms, ', '))
                ELSE 'NULL::bigint[]'
                END,
            CASE
                WHEN 'text' = ANY (l_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::text[]',
                                           pg_catalog.array_to_string(l_text_terms, ', '))
                ELSE 'NULL::text[]'
                END,
            CASE
                WHEN 'uuid' = ANY (l_key_kinds)
                    THEN pg_catalog.format('ARRAY[%s]::uuid[]',
                                           pg_catalog.array_to_string(l_uuid_terms, ', '))
                ELSE 'NULL::uuid[]'
                END);

    INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size, threads,
                                              driving_table_schema_name, driving_table_name)
    VALUES (i_label, i_sql_text, i_chunk_size, i_threads, i_schema_name, i_table_name)
    RETURNING run_id
        INTO l_run_id;

    -- Boundaries are picked by row position, so the computation never needs
    -- min()/max() on the key: PostgreSQL defines those aggregates for the
    -- integer and text types but not for uuid.
    --
    -- The shape matters on production-sized tables:
    --   * numbered is referenced once, so it is inlined and the row_number()
    --     WindowAgg streams over the primary-key index instead of sorting or
    --     materialising all rows.
    --   * count(*) OVER () is deliberately not used: a full-partition window
    --     aggregate buffers every row (spilling to disk for large tables) just
    --     to recover the row count. The terminal high-water boundary instead
    --     comes from a backward index scan (ORDER BY the key DESC LIMIT 1) and
    --     its boundary_no is the last chunk start plus one.
    --   * only the (N / chunk_size) start rows are materialised.
    -- The key column list, ORDER BY and pack expression are built from catalog
    -- data and whitelisted kinds; the run id and chunk size are bound as
    -- parameters ($1, $2).
    EXECUTE pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils_data.migration_boundary (run_id, boundary_no, boundary_id, completed_at)
        WITH numbered AS (
            SELECT
                %1$s,
                row_number() OVER (ORDER BY %2$s) AS rn
            FROM %3$I.%4$I
        ),
        starts AS MATERIALIZED (
            SELECT
                %5$s,
                (rn - 1) / $2::bigint AS boundary_no
            FROM numbered
            WHERE (rn - 1) %% $2::bigint = 0
        )
        SELECT
            $1,
            boundary_no,
            %6$s AS boundary_id,
            NULL::timestamptz AS completed_at
        FROM starts
        UNION ALL
        SELECT
            $1,
            (SELECT max(boundary_no) + 1 FROM starts),
            terminal.boundary_id,
            NULL::timestamptz AS completed_at
        FROM (
            SELECT
                %6$s AS boundary_id
            FROM (
                SELECT %1$s
                FROM %3$I.%4$I
                ORDER BY %7$s
                LIMIT 1
            ) AS last_row
        ) AS terminal
        $chunk$,
            l_key_select_list,
            l_order_by,
            i_schema_name,
            i_table_name,
            l_key_id_list,
            l_pack_expression,
            l_order_by_desc)
        USING l_run_id, i_chunk_size;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_migration_boundaries IS
    'Creates a migration run for the label and populates its fixed-row chunk '
        'boundaries for the given table, returning the new run_id.';
