CREATE OR REPLACE FUNCTION dml_utils.run_migration_chunks(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't'
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name      name;
    l_run_id                bigint;
    l_completed_at          timestamptz;
    l_stored_sql_text       text;
    l_stored_chunk_size     integer;
    l_stored_schema_name    text;
    l_stored_table_name     text;
    l_effective_sql_text    text;
    l_effective_schema_name text;
    l_effective_table_name  text;
    l_key_kind              text;
    l_boundary_no           bigint;
    l_start_value           text;
    l_end_value             text;
    l_is_final              boolean;
    l_chunk_sql             text;
    -- pg_background is installed in the public schema. Qualify its types and
    -- functions explicitly so this routine resolves them regardless of the
    -- caller's (or Liquibase's) search_path.
    l_handle                public.pg_background_handle;
    l_result                public.pg_background_run_result;
BEGIN
    -- Validate the template up front so a bad call fails before any run or
    -- worker exists.
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- Reuse the active run for the label when there is one; otherwise create it
    -- by running populate_migration_boundaries in a worker so it commits
    -- autonomously and the boundaries become visible to the processing workers.
    SELECT run_id,
           completed_at,
           sql_text,
           chunk_size,
           driving_table_schema_name::text,
           driving_table_name::text
    INTO l_run_id, l_completed_at, l_stored_sql_text, l_stored_chunk_size,
        l_stored_schema_name, l_stored_table_name
    FROM dml_utils_data.migration_run
    WHERE label = i_label
      AND archived_at IS NULL;

    IF FOUND AND l_completed_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % already completed at %', i_label, l_completed_at;
        RETURN;
    END IF;

    IF FOUND THEN
        -- A resumed run uses the SQL, chunk size and driving table recorded when
        -- it was created, so an adjusted statement (for example to fix a bad
        -- execution plan) is applied via set_migration_run_sql_text rather than a
        -- changed call. A differing input is ignored, with a notice, so the
        -- boundaries and their chunk SQL are never silently redefined against a
        -- different table.
        l_effective_sql_text := l_stored_sql_text;
        l_effective_schema_name := l_stored_schema_name;
        l_effective_table_name := l_stored_table_name;
        IF l_stored_sql_text IS DISTINCT FROM i_sql_text THEN
            RAISE NOTICE 'run for label % already exists; using the stored sql_text',
                i_label;
        END IF;
        IF l_stored_chunk_size IS DISTINCT FROM i_chunk_size THEN
            RAISE NOTICE 'run for label % already exists; using the stored chunk_size %',
                i_label, l_stored_chunk_size;
        END IF;
        IF l_stored_schema_name IS DISTINCT FROM i_driving_table_schema_name
            OR l_stored_table_name IS DISTINCT FROM i_driving_table_name
        THEN
            RAISE NOTICE 'run for label % already exists; using the stored driving table %.%',
                i_label, l_stored_schema_name, l_stored_table_name;
        END IF;
    ELSE
        l_effective_sql_text := i_sql_text;
        l_effective_schema_name := i_driving_table_schema_name;
        l_effective_table_name := i_driving_table_name;

        l_handle := public.pg_background_launch(pg_catalog.format(
                'SELECT dml_utils_lib.populate_migration_boundaries(%L, %L, %L, %L, %s) AS run_id',
                i_driving_table_schema_name,
                i_driving_table_name,
                i_label,
                i_sql_text,
                i_chunk_size));
        PERFORM public.pg_background_wait(l_handle.pid, l_handle.cookie);

        -- result() is one-time consumption and auto-detaches; it re-raises the
        -- worker's SQLSTATE (for example 23505 on a concurrent same-label run).
        SELECT run_id
        INTO l_run_id
        FROM public.pg_background_result(l_handle.pid, l_handle.cookie) AS (run_id bigint);
    END IF;

    -- Resolve the key from the effective driving table (the input for a new run,
    -- the stored table for a resumed one) before any chunk worker is launched.
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
            i_schema_name => l_effective_schema_name,
            i_table_name => l_effective_table_name);
    l_key_kind := dml_utils_lib.primary_key_kind(
            i_schema_name => l_effective_schema_name,
            i_table_name => l_effective_table_name);

    -- The key extraction below has one arm per known kind. Fail loudly here if
    -- primary_key_kind ever returns a kind this routine does not understand,
    -- rather than letting the extraction fall through to NULL and rendering a
    -- predicate that matches no rows.
    IF l_key_kind NOT IN ('bigint', 'text', 'uuid') THEN
        RAISE EXCEPTION 'unsupported key kind %', l_key_kind
            USING ERRCODE = '22023';
    END IF;

    -- Process every unclaimed boundary in order. Each worker claims its boundary
    -- and runs its chunk SQL in its own transaction, so progress is durable and
    -- a re-run resumes at the first unclaimed boundary.
    --
    -- The chunk's end and final flag come from the run's full ordered boundary
    -- set (boundaries are contiguous 0..N, where N is the terminal high-water
    -- boundary), NOT from the set of still-unclaimed boundaries. Deriving them
    -- from the unclaimed set would misclassify a chunk as final whenever a later
    -- boundary (for example the terminal one) is already completed. The join on
    -- boundary_no + 1 excludes the terminal boundary, so the end key is never
    -- null.
    LOOP
        SELECT b.boundary_no,
               CASE l_key_kind
                   WHEN 'bigint' THEN (b.boundary_id).bigint_value::text
                   WHEN 'text' THEN (b.boundary_id).text_value
                   WHEN 'uuid' THEN (b.boundary_id).uuid_value::text
                   END,
               CASE l_key_kind
                   WHEN 'bigint' THEN (next.boundary_id).bigint_value::text
                   WHEN 'text' THEN (next.boundary_id).text_value
                   WHEN 'uuid' THEN (next.boundary_id).uuid_value::text
                   END,
               next.boundary_no = last.boundary_no
        INTO l_boundary_no, l_start_value, l_end_value, l_is_final
        FROM dml_utils_data.migration_boundary AS b
                 JOIN dml_utils_data.migration_boundary AS next
                      ON next.run_id = b.run_id
                          AND next.boundary_no = b.boundary_no + 1
                 CROSS JOIN LATERAL (
            SELECT max(boundary_no) AS boundary_no
            FROM dml_utils_data.migration_boundary
            WHERE run_id = b.run_id
            ) AS last
        WHERE b.run_id = l_run_id
          AND b.completed_at IS NULL
        ORDER BY b.boundary_no
        LIMIT 1;

        EXIT WHEN NOT FOUND;

        l_chunk_sql := dml_utils_lib.render_chunk_sql(
                i_sql_text => l_effective_sql_text,
                i_schema_name => l_effective_schema_name,
                i_table_name => l_effective_table_name,
                i_table_alias => i_driving_table_alias,
                i_primary_key_name => l_primary_key_name,
                i_key_kind => l_key_kind,
                i_start_value => l_start_value,
                i_end_value => l_end_value,
                i_is_final => l_is_final);

        -- One-shot run: launch + wait + outcome + detach. The worker SQL is
        -- text (pg_background has no USING), so the run id, boundary number and
        -- chunk SQL are inlined: the first two are bigint (%s), the chunk SQL is
        -- a literal (%L). Propagate a worker error with its original SQLSTATE.
        SELECT *
        INTO l_result
        FROM public.pg_background_run(pg_catalog.format(
                                              'SELECT dml_utils_lib.process_migration_chunk(%s, %s, %L)',
                                              l_run_id, l_boundary_no, l_chunk_sql)
            , 0, 0, pg_catalog.format('run %s chunk %s', l_run_id, l_boundary_no));

        IF l_result.has_error THEN
            -- Record the failure first, in its own worker/transaction, so the
            -- row commits autonomously and survives the re-raise below (which
            -- rolls the caller's transaction back). Recording is best effort:
            -- the original error is always re-raised. The ids are bigint (%s);
            -- the SQLSTATE and message are literals (%L).
            PERFORM public.pg_background_run(pg_catalog.format(
                    'SELECT dml_utils_lib.record_migration_error(%s, %s, %L, %L)',
                    l_run_id, l_boundary_no, l_result.sqlstate, l_result.error_message));

            RAISE EXCEPTION 'chunk % for run % failed: %', l_boundary_no, l_run_id,
                l_result.error_message
                USING ERRCODE = l_result.sqlstate;
        END IF;
    END LOOP;

    -- All boundaries are claimed and every chunk SQL already ran; record the run
    -- completion in the caller's transaction.
    UPDATE dml_utils_data.migration_run
    SET completed_at = pg_catalog.now()
    WHERE run_id = l_run_id
      AND completed_at IS NULL;
END;
$$;

COMMENT ON FUNCTION dml_utils.run_migration_chunks IS
    'Runs the chunk SQL for every fixed-row chunk of the driving table, one '
        'pg_background worker per chunk, resuming an active run for the label and '
        'recording its completion. A failed chunk is recorded in '
        'dml_utils_data.migration_error before its error is re-raised.';
