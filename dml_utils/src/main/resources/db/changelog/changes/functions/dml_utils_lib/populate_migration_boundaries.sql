CREATE OR REPLACE FUNCTION dml_utils_lib.populate_migration_boundaries(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_label dml_utils_data.non_null_text,
    i_sql_text dml_utils_data.non_null_text,
    i_chunk_size dml_utils_data.positive_integer,
    i_threads dml_utils_data.positive_integer DEFAULT 1,
    i_chunk_by dml_utils_data.chunking_strategy DEFAULT 'primary_key'
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_columns name[];
    l_key_kinds           text[];
    l_relation_filepath   text;
    l_run_id              bigint;
BEGIN
    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);
    PERFORM dml_utils_lib.assert_no_active_run_for_label(i_label => i_label);

    -- A primary-key run reads the key columns and kinds; a block run instead
    -- records the driving table's physical filepath so a resume can detect a
    -- heap rewrite. The blocks<->filepath check on migration_run then requires
    -- exactly one of the two to be set.
    IF i_chunk_by = 'blocks' THEN
        l_relation_filepath := dml_utils_lib.relation_filepath(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    ELSE
        l_primary_key_columns := dml_utils_lib.primary_key_columns(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
        l_key_kinds := dml_utils_lib.primary_key_kinds(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name);
    END IF;

    -- started_at is the run's start: the boundary calculation begins here. It is
    -- written in this worker's transaction, so it commits with the run and the
    -- boundaries and survives a later failed chunk-processing attempt.
    INSERT INTO dml_utils_data.migration_run (label, sql_text, chunk_size, chunk_by, threads,
                                              driving_table_schema_name, driving_table_name,
                                              driving_table_relation_filepath, started_at)
    VALUES (i_label, i_sql_text, i_chunk_size, i_chunk_by, i_threads, i_schema_name, i_table_name,
            l_relation_filepath, pg_catalog.clock_timestamp())
    RETURNING run_id
        INTO l_run_id;

    -- The boundary statement is built by a dml_utils_lib helper shared with the
    -- explain path, so both execute and explain the identical SQL. It is safe
    -- because every identifier comes from the catalog and every kind from the
    -- primary_key_kinds whitelist; the run id and chunk size are inlined as
    -- bigints. Boundaries are written as the contiguous sequence 0..N in one
    -- statement, so a run has exactly the chunk starts plus one terminal
    -- boundary the runner assumes; this is the only supported write path.
    IF i_chunk_by = 'blocks' THEN
        EXECUTE dml_utils_lib.build_block_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_run_id => l_run_id,
                i_chunk_size => i_chunk_size::bigint);
    ELSE
        EXECUTE dml_utils_lib.build_boundary_population_sql(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_primary_key_columns => l_primary_key_columns,
                i_key_kinds => l_key_kinds,
                i_run_id => l_run_id,
                i_chunk_size => i_chunk_size::bigint);
    END IF;

    -- The range calculation is done once the boundaries are inserted. Record it
    -- in this worker's transaction, so it commits with the run and is the
    -- milestone a resume relies on: the boundaries are never recomputed.
    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp()
    WHERE run_id = l_run_id;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.populate_migration_boundaries IS
    'Creates a migration run for the label and populates its chunk boundaries for '
        'the given table and chunking strategy (primary_key or blocks), returning '
        'the new run_id. Records the run''s started_at when the calculation begins '
        'and boundaries_calculated_at when it finishes, both in the worker''s '
        'transaction so they commit with the run; a block run also records the '
        'driving table''s physical filepath. Boundaries are written as the '
        'contiguous sequence 0..N in one statement; this is the only supported '
        'write path for boundaries.';
