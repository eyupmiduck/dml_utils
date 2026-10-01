CREATE OR REPLACE FUNCTION dml_utils.process_migration_chunk(
    i_run_id      bigint,
    i_boundary_no bigint,
    i_sql_text    dml_utils.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_boundary_no bigint;
BEGIN
    -- Claim the boundary first: a single atomic UPDATE ... RETURNING both locks
    -- the row for this worker's transaction and records the claim. The
    -- completed_at IS NULL guard makes the claim exclusive, so a boundary can be
    -- processed only once. If the chunk SQL then fails, the worker transaction
    -- aborts and the claim rolls back, leaving the boundary unclaimed so a later
    -- run resumes it.
    UPDATE dml_utils.migration_boundary
    SET completed_at = pg_catalog.now()
    WHERE run_id = i_run_id
      AND boundary_no = i_boundary_no
      AND completed_at IS NULL
    RETURNING boundary_no
        INTO l_boundary_no;

    -- No row claimed means the boundary is missing or already completed.
    IF l_boundary_no IS NULL THEN
        RAISE EXCEPTION 'migration boundary % for run % was not found or is already completed',
            i_boundary_no, i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- The chunk SQL is fully formed by dml_utils_lib.render_chunk_sql from
    -- validated identifiers and literal ids; this is the intended dynamic-SQL
    -- boundary for running one chunk.
    EXECUTE i_sql_text;
END;
$$;

COMMENT ON FUNCTION dml_utils.process_migration_chunk IS
    'Claims one migration boundary and runs its chunk SQL in the caller''s '
        'transaction; raises P0002 when the boundary is missing or already '
        'completed. Intended to run inside a pg_background worker.';
