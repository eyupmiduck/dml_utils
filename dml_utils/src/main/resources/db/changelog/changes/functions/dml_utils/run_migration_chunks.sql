CREATE OR REPLACE FUNCTION dml_utils.run_migration_chunks(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer DEFAULT 1000,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    i_driving_table_alias dml_utils_data.non_null_text DEFAULT 't',
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key'
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns   name[];
    l_key_kinds             text[];
    l_chunk_by              dml_utils_data.chunking_strategy;
    l_run_id                bigint;
    l_already_completed     boolean;
    l_effective_sql_text    text;
    l_effective_threads     integer;
    l_effective_schema_name text;
    l_effective_table_name  text;
    l_effective_alias       text;
    l_boundary_no           bigint;
    l_start_values          text[];
    l_end_values            text[];
    l_is_final              boolean;
    l_chunk_sql             text;
    -- The workers kept in flight, and the boundary each one was launched for
    -- (parallel arrays, aligned by position).
    l_in_flight             public.pg_background_handle[] := ARRAY []::public.pg_background_handle[];
    l_in_flight_boundaries  bigint[]                      := ARRAY []::bigint[];
    l_worker_index          integer;
    l_aborting              boolean                       := false;
    l_error_sqlstate        text;
    l_error_message         text;
    l_error_boundary_no     bigint;
    -- pg_background is installed in the public schema. Qualify its types and
    -- functions explicitly so this routine resolves them regardless of the
    -- caller's (or Liquibase's) search_path.
    l_handle                public.pg_background_handle;
    l_outcome               public.pg_background_outcome;
BEGIN
    -- Validate the template up front so a bad call fails before any run or
    -- worker exists.
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- Resolve or create the run and the effective inputs (stored values for a
    -- resumed run, the call's inputs for a new one); a new run's boundaries are
    -- populated in a pg_background worker so they commit autonomously.
    SELECT r.o_run_id,
           r.o_already_completed,
           r.o_effective_sql_text,
           r.o_effective_schema_name,
           r.o_effective_table_name,
           r.o_effective_alias,
           r.o_effective_threads,
           r.o_chunk_by,
           r.o_primary_key_columns,
           r.o_key_kinds
    INTO l_run_id, l_already_completed, l_effective_sql_text,
        l_effective_schema_name, l_effective_table_name, l_effective_alias,
        l_effective_threads, l_chunk_by, l_primary_key_columns, l_key_kinds
    FROM dml_utils_lib.resolve_migration_run(
                 i_sql_text => i_sql_text,
                 i_driving_table_schema_name => i_driving_table_schema_name,
                 i_driving_table_name => i_driving_table_name,
                 i_label => i_label,
                 i_chunk_size => i_chunk_size,
                 i_threads => i_threads,
                 i_driving_table_alias => i_driving_table_alias,
                 i_chunk_by => i_chunk_by) AS r;

    IF l_already_completed THEN
        RETURN;
    END IF;

    -- started_at (the run start) and boundaries_calculated_at (the range
    -- calculation completion) were recorded by populate_migration_boundaries
    -- when the run was created, in its worker's transaction, so they persist
    -- across a failed processing attempt and are not re-stamped on a resume.
    -- Only completed_at, below, is written in the caller's transaction.

    -- Process every unclaimed boundary, up to i_threads workers at a time. Each
    -- worker claims its boundary and runs its chunk SQL in its own transaction,
    -- so progress is durable and a re-run resumes at the first unclaimed
    -- boundary. The coordinator only schedules; it holds no locks on the
    -- driving table.
    --
    -- The chunk's end and final flag come from the run's full ordered boundary
    -- set (boundaries are contiguous 0..N, where N is the terminal high-water
    -- boundary), NOT from the set of still-unclaimed boundaries. Deriving them
    -- from the unclaimed set would misclassify a chunk as final whenever a later
    -- boundary (for example the terminal one) is already completed. The join on
    -- boundary_no + 1 excludes the terminal boundary, so the end key is never
    -- null.
    BEGIN
        LOOP
            -- Keep launching until i_threads are in flight (or there is nothing left
            -- to launch), unless a worker has already failed.
            IF NOT l_aborting THEN
                WHILE pg_catalog.cardinality(l_in_flight) < l_effective_threads
                    LOOP
                    -- Extract the per-position key values of the start and end
                    -- boundaries. Each kind populates a different position-aligned
                    -- array of migration_key; the extract below flattens them back
                    -- into a value list, in primary-key order.
                        SELECT b.boundary_no,
                               dml_utils_lib.migration_key_values(
                                       i_key => b.boundary_id,
                                       i_key_kinds => l_key_kinds),
                               dml_utils_lib.migration_key_values(
                                       i_key => next.boundary_id,
                                       i_key_kinds => l_key_kinds),
                               next.boundary_no = last.boundary_no
                        INTO l_boundary_no, l_start_values, l_end_values, l_is_final
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
                          AND b.boundary_no <> ALL (l_in_flight_boundaries)
                        ORDER BY b.boundary_no
                        LIMIT 1;

                        EXIT WHEN NOT FOUND;

                        IF l_chunk_by = 'blocks' THEN
                            -- The boundary values are the start and end block
                            -- numbers; render a half-open ctid range. There is no
                            -- final/inclusive distinction for blocks.
                            l_chunk_sql := dml_utils_lib.render_block_chunk_sql(
                                    i_sql_text => l_effective_sql_text,
                                    i_schema_name => l_effective_schema_name,
                                    i_table_name => l_effective_table_name,
                                    i_table_alias => l_effective_alias,
                                    i_start_block => l_start_values[1]::bigint,
                                    i_end_block => l_end_values[1]::bigint);
                        ELSE
                            l_chunk_sql := dml_utils_lib.render_chunk_sql(
                                    i_sql_text => l_effective_sql_text,
                                    i_schema_name => l_effective_schema_name,
                                    i_table_name => l_effective_table_name,
                                    i_table_alias => l_effective_alias,
                                    i_primary_key_columns => l_primary_key_columns,
                                    i_key_kinds => l_key_kinds,
                                    i_start_values => l_start_values,
                                    i_end_values => l_end_values,
                                    i_is_final => l_is_final);
                        END IF;

                        -- pg_background has no USING, so the run id, boundary number and
                        -- chunk SQL are inlined: the first two are bigint (%s), the chunk
                        -- SQL is a literal (%L).
                        l_handle := public.pg_background_launch(
                                pg_catalog.format(
                                        'SELECT dml_utils_lib.process_migration_chunk(%s, %s, %L)',
                                        l_run_id, l_boundary_no, l_chunk_sql),
                                0,
                                pg_catalog.format('run %s chunk %s', l_run_id, l_boundary_no));
                        l_in_flight := l_in_flight || l_handle;
                        l_in_flight_boundaries := l_in_flight_boundaries || l_boundary_no;
                    END LOOP;
            END IF;

            -- Nothing running and nothing left to launch: done (or draining is done).
            EXIT WHEN pg_catalog.cardinality(l_in_flight) = 0;

            -- Wait for any in-flight worker to finish. wait_any() with a zero timeout
            -- does not block, so poll with a short timeout.
            LOOP
                l_handle := public.pg_background_wait_any(l_in_flight, 1000);
                EXIT WHEN l_handle IS NOT NULL;
            END LOOP;

            -- Recover the boundary the finished worker was launched for, then drop it
            -- from the in-flight set.
            l_worker_index := pg_catalog.array_position(l_in_flight, l_handle);
            l_boundary_no := l_in_flight_boundaries[l_worker_index];
            l_in_flight := l_in_flight[1:l_worker_index - 1]
                || l_in_flight[l_worker_index + 1:];
            l_in_flight_boundaries := l_in_flight_boundaries[1:l_worker_index - 1]
                || l_in_flight_boundaries[l_worker_index + 1:];

            SELECT *
            INTO l_outcome
            FROM public.pg_background_outcome(l_handle.pid, l_handle.cookie);
            PERFORM public.pg_background_detach(l_handle.pid, l_handle.cookie);

            IF l_outcome.has_error THEN
                -- P0002 means the boundary was already claimed and completed by
                -- another coordinator; that is benign, so do not record an error or
                -- abort this coordinator.
                IF l_outcome.sqlstate = 'P0002' THEN
                    CONTINUE;
                END IF;

                -- Record the failure first, in its own worker/transaction, so the
                -- row commits autonomously and survives the re-raise below. Recording
                -- is best effort: if it fails (for example the recording worker cannot
                -- be launched), the original worker error is still raised. The ids are
                -- bigint (%s); the SQLSTATE and message are literals (%L).
                BEGIN
                    PERFORM public.pg_background_run(pg_catalog.format(
                            'SELECT dml_utils_lib.record_migration_error(%s, %s, %L, %L)',
                            l_run_id, l_boundary_no, l_outcome.sqlstate, l_outcome.error_message));
                EXCEPTION
                    WHEN OTHERS THEN
                        NULL;
                END;

                -- Remember the first failure and stop launching new chunks: the
                -- workers already in flight finish (and commit) their current chunk,
                -- then this call exits with the error.
                IF NOT l_aborting THEN
                    l_error_sqlstate := l_outcome.sqlstate;
                    l_error_message := l_outcome.error_message;
                    l_error_boundary_no := l_boundary_no;
                END IF;
                l_aborting := true;
            END IF;
        END LOOP;

        -- l_aborting is the failure latch (not l_error_sqlstate, which a worker could
        -- in principle report as NULL).
        IF l_aborting THEN
            RAISE EXCEPTION 'chunk % for run % failed: %', l_error_boundary_no, l_run_id,
                l_error_message
                USING ERRCODE = COALESCE(l_error_sqlstate, 'P0001');
        END IF;
    EXCEPTION
        WHEN OTHERS THEN
            -- An unexpected error (a failed launch, a render error, ...) would
            -- otherwise abandon the workers already in flight. Wait for each to
            -- finish and detach it, so it commits/finishes and its handle does not
            -- leak, then re-raise the original error.
            FOR l_drain_index IN 1..pg_catalog.cardinality(l_in_flight)
                LOOP
                    BEGIN
                        PERFORM public.pg_background_wait(
                                l_in_flight[l_drain_index].pid,
                                l_in_flight[l_drain_index].cookie);
                        PERFORM public.pg_background_detach(
                                l_in_flight[l_drain_index].pid,
                                l_in_flight[l_drain_index].cookie);
                    EXCEPTION
                        WHEN OTHERS THEN
                            NULL;
                    END;
                END LOOP;
            RAISE;
    END;

    -- All boundaries are claimed and every chunk SQL already ran; record the run
    -- completion in the caller's transaction. clock_timestamp() so completed_at
    -- is the actual server time the run finished, not the transaction start.
    UPDATE dml_utils_data.migration_run
    SET completed_at = pg_catalog.clock_timestamp()
    WHERE run_id = l_run_id
      AND completed_at IS NULL;
END;
$$;

COMMENT ON FUNCTION dml_utils.run_migration_chunks IS
    'Runs the chunk SQL for every chunk of the driving table (primary_key: fixed '
        'rows in primary-key order; blocks: half-open physical ctid ranges), up to '
        'i_threads pg_background workers at a time, resuming an active run for '
        'the label and recording its completion. A resumed run uses the stored '
        'sql_text, chunk size, strategy, threads, driving table and alias. On a '
        'failed chunk it stops launching, lets the in-flight chunks commit, drains '
        'and detaches them, and re-raises the error; the failure is recorded (best '
        'effort) in dml_utils_data.migration_error first. A boundary already '
        'claimed by another coordinator (P0002) is treated as benign.';
