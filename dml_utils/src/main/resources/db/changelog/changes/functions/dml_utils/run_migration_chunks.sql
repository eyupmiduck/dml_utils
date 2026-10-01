CREATE OR REPLACE FUNCTION dml_utils.run_migration_chunks(
    i_sql_text                  dml_utils.non_null_text,
    i_driving_table_schema_name dml_utils.non_null_text,
    i_driving_table_name        dml_utils.non_null_text,
    i_label                     dml_utils.non_null_text,
    i_chunk_size                dml_utils.positive_integer,
    i_driving_table_alias       dml_utils.non_null_text DEFAULT 't'
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name name;
    l_run_id           bigint;
    l_completed_at     timestamptz;
    l_boundary_no      bigint;
    l_start_id         bigint;
    l_end_id           bigint;
    l_is_final         boolean;
    l_chunk_sql        text;
    -- pg_background is installed in the public schema. Qualify its types and
    -- functions explicitly so this routine resolves them regardless of the
    -- caller's (or Liquibase's) search_path.
    l_handle           public.pg_background_handle;
    l_result           public.pg_background_run_result;
BEGIN
    -- Validate the template and the driving table up front so a bad call fails
    -- before any run or worker exists.
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
            i_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name);
    PERFORM dml_utils_lib.assert_bigint_primary_key(
            i_schema_name => i_driving_table_schema_name,
            i_table_name => i_driving_table_name);

    -- Reuse the active run for the label when there is one; otherwise create it
    -- by running populate_migration_boundaries in a worker so it commits
    -- autonomously and the boundaries become visible to the processing workers.
    SELECT run_id, completed_at
    INTO l_run_id, l_completed_at
    FROM dml_utils.migration_run
    WHERE label = i_label
      AND archived_at IS NULL;

    IF FOUND AND l_completed_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % already completed at %', i_label, l_completed_at;
        RETURN;
    END IF;

    IF NOT FOUND THEN
        l_handle := public.pg_background_launch(pg_catalog.format(
                'SELECT dml_utils.populate_migration_boundaries(%L, %L, %L, %L, %s) AS run_id',
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

    -- Process every unclaimed boundary in order. Each worker claims its boundary
    -- and runs its chunk SQL in its own transaction, so progress is durable and
    -- a re-run resumes at the first unclaimed boundary.
    LOOP
        SELECT b.boundary_no,
               b.boundary_id,
               lead(b.boundary_id, 1) OVER w,
               lead(b.boundary_id, 2) OVER w IS NULL
        INTO l_boundary_no, l_start_id, l_end_id, l_is_final
        FROM dml_utils.migration_boundary AS b
        WHERE b.run_id = l_run_id
          AND b.completed_at IS NULL
        WINDOW w AS (ORDER BY b.boundary_no)
        ORDER BY b.boundary_no
        LIMIT 1;

        EXIT WHEN NOT FOUND;

        -- The last boundary is terminal: it has no following boundary to run as
        -- a chunk, so end_id is null. It exists only to bound the final chunk.
        EXIT WHEN l_end_id IS NULL;

        l_chunk_sql := dml_utils_lib.render_chunk_sql(
                i_sql_text => i_sql_text,
                i_schema_name => i_driving_table_schema_name,
                i_table_name => i_driving_table_name,
                i_table_alias => i_driving_table_alias,
                i_primary_key_name => l_primary_key_name,
                i_start_id => l_start_id,
                i_end_id => l_end_id,
                i_is_final => l_is_final);

        -- One-shot run: launch + wait + outcome + detach. The worker SQL is
        -- text (pg_background has no USING), so the run id, boundary number and
        -- chunk SQL are inlined: the first two are bigint (%s), the chunk SQL is
        -- a literal (%L). Propagate a worker error with its original SQLSTATE.
        SELECT *
        INTO l_result
        FROM public.pg_background_run(pg_catalog.format(
                'SELECT dml_utils.process_migration_chunk(%s, %s, %L)',
                l_run_id, l_boundary_no, l_chunk_sql)
            , 0, 0, pg_catalog.format('run %s chunk %s', l_run_id, l_boundary_no));

        IF l_result.has_error THEN
            RAISE EXCEPTION 'chunk % for run % failed: %', l_boundary_no, l_run_id,
                l_result.error_message
                USING ERRCODE = l_result.sqlstate;
        END IF;
    END LOOP;

    -- All boundaries are claimed and every chunk SQL already ran; record the run
    -- completion in the caller's transaction.
    UPDATE dml_utils.migration_run
    SET completed_at = pg_catalog.now()
    WHERE run_id = l_run_id
      AND completed_at IS NULL;
END;
$$;

COMMENT ON FUNCTION dml_utils.run_migration_chunks IS
    'Runs the chunk SQL for every fixed-row chunk of the driving table, one '
        'pg_background worker per chunk, resuming an active run for the label and '
        'recording its completion.';
