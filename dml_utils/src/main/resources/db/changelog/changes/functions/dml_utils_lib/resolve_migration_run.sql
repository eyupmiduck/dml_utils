CREATE OR REPLACE FUNCTION dml_utils_lib.resolve_migration_run(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer,
    i_driving_table_alias dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    -- Initial delay for a new run; a resumed run uses its stored value.
    i_sleep_ms dml_utils_data.non_negative_integer DEFAULT 0,
    -- The unique index whose order an ordered_key run uses. Required for a new
    -- ordered_key run; ignored with a notice when resuming an existing run, where
    -- the stored strategy and index win.
    i_boundary_key_index name DEFAULT NULL,
    -- When true, the driving table is a declarative partitioned parent and the
    -- run's boundaries are calculated leaf by leaf. The stored flag wins on resume.
    i_partition_wise dml_utils_data.non_null_boolean DEFAULT FALSE,
    -- Initial transient-retry policy for a new run; a resumed run uses its stored
    -- values.
    i_max_retries dml_utils_data.non_negative_integer DEFAULT 3,
    i_retry_backoff_ms dml_utils_data.non_negative_integer DEFAULT 1000,
    OUT o_run_id bigint,
    OUT o_already_completed boolean,
    OUT o_effective_sql_text text,
    OUT o_effective_schema_name text,
    OUT o_effective_table_name text,
    OUT o_effective_alias text,
    OUT o_effective_threads integer,
    OUT o_effective_sleep_ms integer,
    OUT o_effective_max_retries integer,
    OUT o_effective_retry_backoff_ms integer,
    OUT o_chunk_by dml_utils_data.chunking_strategy,
    OUT o_partition_wise boolean,
    OUT o_key_columns name[],
    OUT o_key_kinds text[]
)
    RETURNS record
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_run        dml_utils_data.migration_run%ROWTYPE;
    l_effective  record;
    l_key        record;
    l_run_found  boolean;
    l_constraint text;
BEGIN
    -- A NULL strategy would fall through the resume/create branches and behave
    -- like primary_key; the column is NOT NULL, so it can only come from a direct
    -- caller.
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- Reuse the active run for the label when there is one; otherwise create it
    -- by running populate_migration_boundaries in a worker so it commits
    -- autonomously and the boundaries become visible to the processing workers.
    --
    -- The lookup outcome is materialized in l_run_found immediately: FOUND is
    -- reset by every later statement, and the resume-vs-create decision must not
    -- depend on it surviving. The whole row is selected, so a new migration_run
    -- column cannot break this assignment the way a copied column list would.
    l_run := (SELECT active_run
              FROM dml_utils_lib.find_active_migration_run(i_label => i_label) AS active_run);
    l_run_found := l_run.run_id IS NOT NULL;

    IF NOT l_run_found THEN
        -- Validate the requested count before creating anything, so a rejected
        -- value cannot leave a run (and its boundaries) behind.
        PERFORM dml_utils_lib.assert_threads_within_max_worker_processes(
                i_threads => i_threads);

        -- Two sessions can resolve the same new label together. The partial
        -- unique index lets exactly one create win; the loser takes the winner's
        -- run below instead of surfacing the raw 23505. Only the label index is
        -- retried: any other unique violation is a real error.
        BEGIN
            PERFORM dml_utils_lib.create_migration_run(
                    i_schema_name => i_driving_table_schema_name,
                    i_table_name => i_driving_table_name,
                    i_label => i_label,
                    i_sql_text => i_sql_text,
                    i_chunk_size => i_chunk_size,
                    i_threads => i_threads,
                    i_chunk_by => i_chunk_by,
                    i_driving_table_alias => i_driving_table_alias,
                    i_sleep_ms => i_sleep_ms,
                    i_boundary_key_index => i_boundary_key_index,
                    i_partition_wise => i_partition_wise,
                    i_max_retries => i_max_retries,
                    i_retry_backoff_ms => i_retry_backoff_ms);
        EXCEPTION
            WHEN unique_violation THEN
                GET STACKED DIAGNOSTICS l_constraint = CONSTRAINT_NAME;
                IF l_constraint <> 'migration_run_label_active_idx' THEN
                    RAISE;
                END IF;
        END;

        l_run := (SELECT active_run
                  FROM dml_utils_lib.find_active_migration_run(i_label => i_label) AS active_run);
        l_run_found := l_run.run_id IS NOT NULL;

        IF NOT l_run_found THEN
            RAISE EXCEPTION 'run for label % was created concurrently but is not visible; retry',
                i_label
                USING ERRCODE = '55000';
        END IF;
    END IF;

    -- Both paths converge here: l_run is the committed stored run, so every
    -- effective output is read from it rather than from the call's arguments.
    IF l_run.completed_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % already completed at %', i_label, l_run.completed_at;
        o_run_id := l_run.run_id;
        o_already_completed := true;
        -- The remaining outputs stay NULL on this path: the caller stops at
        -- o_already_completed, and this contract is part of the routine comment.
        RETURN;
    END IF;

    o_already_completed := false;

    -- A background worker per chunk: max_worker_processes is a cluster-wide
    -- ceiling, not the free capacity for this call (other workers may hold slots),
    -- so this is a cheap early sanity check, not a capacity guarantee. A launch
    -- that still fails partway leaves the launched chunks committed and the run
    -- resumable, which the caller retries. The setter uses the same check, so a
    -- stored count cannot be one this call would reject. This also validates the
    -- stored count of a run another session created concurrently.
    PERFORM dml_utils_lib.assert_threads_within_max_worker_processes(
            i_threads => l_run.threads::dml_utils_data.positive_integer);

    SELECT effective.o_effective_sql_text,
           effective.o_effective_schema_name,
           effective.o_effective_table_name,
           effective.o_effective_alias,
           effective.o_effective_threads,
           effective.o_effective_sleep_ms,
           effective.o_effective_max_retries,
           effective.o_effective_retry_backoff_ms,
           effective.o_chunk_by,
           effective.o_partition_wise
    INTO l_effective
    FROM dml_utils_lib.resume_migration_run(
                 i_run => l_run,
                 i_driving_table_schema_name => i_driving_table_schema_name,
                 i_driving_table_name => i_driving_table_name,
                 i_sql_text => i_sql_text,
                 i_chunk_size => i_chunk_size,
                 i_threads => i_threads,
                 i_driving_table_alias => i_driving_table_alias,
                 i_chunk_by => i_chunk_by,
                 i_sleep_ms => i_sleep_ms,
                 i_boundary_key_index => i_boundary_key_index,
                 i_partition_wise => i_partition_wise,
                 i_max_retries => i_max_retries,
                 i_retry_backoff_ms => i_retry_backoff_ms) AS effective;

    o_run_id := l_run.run_id;
    o_effective_sql_text := l_effective.o_effective_sql_text;
    o_effective_schema_name := l_effective.o_effective_schema_name;
    o_effective_table_name := l_effective.o_effective_table_name;
    o_effective_alias := l_effective.o_effective_alias;
    o_effective_threads := l_effective.o_effective_threads;
    o_effective_sleep_ms := l_effective.o_effective_sleep_ms;
    o_effective_max_retries := l_effective.o_effective_max_retries;
    o_effective_retry_backoff_ms := l_effective.o_effective_retry_backoff_ms;
    o_chunk_by := l_effective.o_chunk_by;
    o_partition_wise := l_effective.o_partition_wise;

    -- Resolve the key the coordinator uses to read boundary values. The run row
    -- stores the ordered descriptor and the primary-key run resolves its real
    -- primary key; the block renderer uses a placeholder column name.
    IF o_chunk_by = 'blocks' THEN
        o_key_columns := ARRAY ['ctid']::name[];
        o_key_kinds := ARRAY ['bigint'];
    ELSE
        SELECT key.o_key_columns,
               key.o_key_kinds
        INTO l_key
        FROM dml_utils_lib.migration_run_boundary_key(i_run_id => o_run_id) AS key;

        o_key_columns := l_key.o_key_columns;
        o_key_kinds := l_key.o_key_kinds;
        IF o_key_columns IS NULL OR o_key_kinds IS NULL THEN
            RAISE EXCEPTION 'run % has no stored boundary key', o_run_id
                USING ERRCODE = '55000';
        END IF;
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.resolve_migration_run(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.positive_integer,
    dml_utils_data.positive_integer,
    dml_utils_data.non_null_text,
    dml_utils_data.chunking_strategy,
    dml_utils_data.non_negative_integer,
    name,
    dml_utils_data.non_null_boolean,
    dml_utils_data.non_negative_integer,
    dml_utils_data.non_negative_integer
    ) IS
    'Resolves or creates the active run for the label and returns the inputs the '
        'coordinator should use: the run id, whether it was already complete, the '
        'effective sql_text/schema/table/alias/threads/sleep_ms/retry-policy and '
        'chunking strategy (stored values for a resumed run), whether the run is '
        'partition-wise, and the boundary key columns and kinds (the stored '
        'ordered-key descriptor for ordered_key, the real primary key for '
        'primary_key, or a single bigint block number for blocks). '
        'find_active_migration_run, resume_migration_run and create_migration_run '
        'own the lookup, resume validation and autonomous creation. A new label '
        'that another session created concurrently resolves to the winner''s run '
        'instead of raising the label index''s 23505. When the run is already '
        'complete, only o_run_id and o_already_completed are set and the other '
        'outputs stay NULL: the caller stops on that flag. A resumed run fails '
        'closed if the driving table''s physical filepath changed, and a resumed '
        'ordered-key run re-validates its stored index and descriptor, raising '
        'object_not_in_prerequisite_state (55000) if the index no longer matches.';
