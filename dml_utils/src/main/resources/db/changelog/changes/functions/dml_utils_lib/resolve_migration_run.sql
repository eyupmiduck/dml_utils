CREATE OR REPLACE FUNCTION dml_utils_lib.resolve_migration_run(
    i_sql_text dml_utils_data.non_null_text,
    i_driving_table_schema_name dml_utils_data.non_null_text,
    i_driving_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer,
    i_driving_table_alias dml_utils_data.non_null_text,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key',
    OUT o_run_id bigint,
    OUT o_already_completed boolean,
    OUT o_effective_sql_text text,
    OUT o_effective_schema_name text,
    OUT o_effective_table_name text,
    OUT o_effective_alias text,
    OUT o_effective_threads integer,
    OUT o_chunk_by dml_utils_data.chunking_strategy,
    OUT o_primary_key_columns name[],
    OUT o_key_kinds text[]
)
    RETURNS record
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_completed_at       timestamptz;
    l_stored_sql_text    text;
    l_stored_chunk_size  integer;
    l_stored_chunk_by    dml_utils_data.chunking_strategy;
    l_stored_threads     integer;
    l_stored_schema_name text;
    l_stored_table_name  text;
    l_stored_alias       text;
    l_stored_filepath    text;
    l_handle             public.pg_background_handle;
    l_worker_run_id      bigint;
BEGIN
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- Reuse the active run for the label when there is one; otherwise create it
    -- by running populate_migration_boundaries in a worker so it commits
    -- autonomously and the boundaries become visible to the processing workers.
    SELECT run_id,
           completed_at,
           sql_text,
           chunk_size,
           chunk_by,
           threads::integer,
           driving_table_schema_name::text,
           driving_table_name::text,
           driving_table_alias::text,
           driving_table_relation_filepath
    INTO o_run_id, l_completed_at, l_stored_sql_text, l_stored_chunk_size,
        l_stored_chunk_by, l_stored_threads, l_stored_schema_name, l_stored_table_name,
        l_stored_alias, l_stored_filepath
    FROM dml_utils_data.migration_run
    WHERE label = i_label
      AND archived_at IS NULL;

    IF FOUND AND l_completed_at IS NOT NULL THEN
        RAISE NOTICE 'run for label % already completed at %', i_label, l_completed_at;
        o_already_completed := true;
        RETURN;
    END IF;

    -- The stored thread count wins for a resumed run. Each chunk is one
    -- background worker; max_worker_processes is a cluster-wide ceiling, not the
    -- free capacity for this call (other workers may hold slots), so this is a
    -- cheap early sanity check, not a capacity guarantee. A launch that still
    -- fails partway leaves the launched chunks committed and the run resumable,
    -- which the caller retries.
    o_effective_threads := CASE WHEN FOUND THEN l_stored_threads ELSE i_threads::integer END;
    IF o_effective_threads > pg_catalog.current_setting('max_worker_processes')::integer THEN
        RAISE EXCEPTION 'threads (%) exceeds max_worker_processes (%)',
            o_effective_threads, pg_catalog.current_setting('max_worker_processes')
            USING ERRCODE = '22023';
    END IF;

    IF FOUND THEN
        -- A resumed run uses the SQL, chunk size, strategy, threads and driving
        -- table recorded when it was created, so an adjusted statement (for
        -- example to fix a bad execution plan) is applied via
        -- set_migration_run_sql_text rather than a changed call. A differing
        -- input is ignored, with a notice, so the boundaries and their chunk SQL
        -- are never silently redefined.
        o_effective_sql_text := l_stored_sql_text;
        o_effective_schema_name := l_stored_schema_name;
        o_effective_table_name := l_stored_table_name;
        o_effective_alias := l_stored_alias;
        o_chunk_by := l_stored_chunk_by;
        IF l_stored_chunk_by IS DISTINCT FROM i_chunk_by THEN
            RAISE NOTICE 'run for label % already exists; using the stored chunk_by %',
                i_label, l_stored_chunk_by;
        END IF;
        IF l_stored_alias IS DISTINCT FROM i_driving_table_alias THEN
            RAISE NOTICE 'run for label % already exists; using the stored driving table alias %',
                i_label, l_stored_alias;
        END IF;
        IF l_stored_sql_text IS DISTINCT FROM i_sql_text THEN
            RAISE NOTICE 'run for label % already exists; using the stored sql_text', i_label;
        END IF;
        IF l_stored_chunk_size IS DISTINCT FROM i_chunk_size THEN
            RAISE NOTICE 'run for label % already exists; using the stored chunk_size %',
                i_label, l_stored_chunk_size;
        END IF;
        IF l_stored_threads IS DISTINCT FROM i_threads::integer THEN
            RAISE NOTICE 'run for label % already exists; using the stored threads %',
                i_label, l_stored_threads;
        END IF;
        IF l_stored_schema_name IS DISTINCT FROM i_driving_table_schema_name
            OR l_stored_table_name IS DISTINCT FROM i_driving_table_name
        THEN
            RAISE NOTICE 'run for label % already exists; using the stored driving table %.%',
                i_label, l_stored_schema_name, l_stored_table_name;
        END IF;

        -- A block run records the physical filepath its boundaries were computed
        -- against; if the heap was rewritten since, the stored block boundaries
        -- are stale, so fail closed rather than resume against unrelated data. A
        -- primary-key run stored a NULL filepath, so this is a no-op for it.
        PERFORM dml_utils_lib.assert_relation_filepath(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name,
                i_expected_filepath => l_stored_filepath);
    ELSE
        o_effective_sql_text := i_sql_text;
        o_effective_schema_name := i_driving_table_schema_name;
        o_effective_table_name := i_driving_table_name;
        o_effective_alias := i_driving_table_alias;
        o_chunk_by := i_chunk_by;

        l_handle := public.pg_background_launch(pg_catalog.format(
                'SELECT dml_utils_lib.populate_migration_boundaries(%L, %L, %L, %L, %s, %s, %L) AS run_id',
                i_driving_table_schema_name,
                i_driving_table_name,
                i_label,
                i_sql_text,
                i_chunk_size,
                i_threads,
                i_chunk_by::text));
        PERFORM public.pg_background_wait(l_handle.pid, l_handle.cookie);

        -- result() is one-time consumption and auto-detaches; it re-raises the
        -- worker's SQLSTATE (for example 23505 on a concurrent same-label run).
        SELECT run_id
        INTO l_worker_run_id
        FROM public.pg_background_result(l_handle.pid, l_handle.cookie) AS (run_id bigint);
        o_run_id := l_worker_run_id;

        -- Persist the alias with the run so a later resume renders with the same
        -- alias the first call used; on a resume the caller's alias is ignored
        -- like the other creation-time inputs.
        UPDATE dml_utils_data.migration_run
        SET driving_table_alias = i_driving_table_alias
        WHERE run_id = o_run_id;
    END IF;

    -- Resolve the key the coordinator uses to read boundary values: the real
    -- primary key for primary_key, or a single bigint (the block number) for
    -- blocks. The placeholder column name is unused by the block renderer.
    IF o_chunk_by = 'blocks' THEN
        o_primary_key_columns := ARRAY ['ctid']::name[];
        o_key_kinds := ARRAY ['bigint'];
    ELSE
        o_primary_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name);
        o_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => o_effective_schema_name,
                i_table_name => o_effective_table_name);
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.resolve_migration_run IS
    'Resolves or creates the active run for the label and returns the inputs the '
        'coordinator should use: the run id, whether it was already complete, the '
        'effective sql_text/schema/table/alias/threads and chunking strategy '
        '(stored values for a resumed run), and the boundary key columns and kinds '
        '(the real primary key for primary_key, or a single bigint block number '
        'for blocks). A new run is created by populating its boundaries in a '
        'pg_background worker; a resumed block run fails closed if the driving '
        'table''s physical filepath changed.';
