CREATE OR REPLACE FUNCTION dml_utils.populate_migration_boundaries(
    i_schema_name dml_utils.non_null_text,
    i_table_name dml_utils.non_null_text,
    i_label dml_utils.non_null_text,
    i_sql_text dml_utils.non_null_text,
    i_chunk_size dml_utils.positive_integer
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name name;
    l_key_kind         text;
    l_pack_expression  text;
    l_run_id           bigint;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    l_key_kind := dml_utils_lib.primary_key_kind(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    PERFORM dml_utils_lib.assert_no_active_run_for_label(i_label => i_label);

    -- The pack expression below has one arm per known kind and is interpolated
    -- into the dynamic SQL. Fail loudly if primary_key_kind ever returns a kind
    -- this routine does not understand, rather than letting the CASE fall
    -- through to NULL and rendering a malformed statement.
    IF l_key_kind NOT IN ('bigint', 'text', 'uuid') THEN
        RAISE EXCEPTION 'unsupported key kind %', l_key_kind
            USING ERRCODE = '22023';
    END IF;

    -- Pack the row's own key into migration_key; each kind populates a
    -- different attribute. The expression is built from a whitelisted kind, so
    -- interpolating it into the dynamic SQL is safe.
    l_pack_expression := CASE l_key_kind
                             WHEN 'bigint' THEN
                                 'ROW(id::bigint, NULL, NULL)::dml_utils.migration_key'
                             WHEN 'text' THEN
                                 'ROW(NULL, id::text, NULL)::dml_utils.migration_key'
                             WHEN 'uuid' THEN
                                 'ROW(NULL, NULL, id::uuid)::dml_utils.migration_key'
        END;

    INSERT INTO dml_utils.migration_run (label, sql_text, chunk_size, driving_table_schema_name,
                                         driving_table_name)
    VALUES (i_label, i_sql_text, i_chunk_size, i_schema_name, i_table_name)
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
    --     comes from a backward index scan (ORDER BY pk DESC LIMIT 1) and its
    --     boundary_no is the last chunk start plus one.
    --   * only the (N / chunk_size) start rows are materialised.
    -- The primary-key column is the only dynamic identifier (%4$s is a
    -- whitelisted packing expression); the run id and chunk size are bound as
    -- parameters ($1, $2).
    EXECUTE pg_catalog.format(
            $chunk$
        INSERT INTO dml_utils.migration_boundary (run_id, boundary_no, boundary_id, completed_at)
        WITH numbered AS (
            SELECT
                %1$I AS id,
                row_number() OVER (ORDER BY %1$I) AS rn
            FROM %2$I.%3$I
        ),
        starts AS MATERIALIZED (
            SELECT
                id,
                (rn - 1) / $2::bigint AS boundary_no
            FROM numbered
            WHERE (rn - 1) %% $2::bigint = 0
        )
        SELECT
            $1,
            boundary_no,
            %4$s AS boundary_id,
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
                %4$s AS boundary_id
            FROM (
                SELECT %1$I AS id
                FROM %2$I.%3$I
                ORDER BY %1$I DESC
                LIMIT 1
            ) AS last_row
        ) AS terminal
        $chunk$,
            l_primary_key_name,
            i_schema_name,
            i_table_name,
            l_pack_expression)
        USING l_run_id, i_chunk_size;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.populate_migration_boundaries IS
    'Creates a migration run for the label and populates its fixed-row chunk '
        'boundaries for the given table, returning the new run_id.';
