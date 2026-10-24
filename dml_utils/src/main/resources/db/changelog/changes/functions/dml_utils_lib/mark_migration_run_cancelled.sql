CREATE OR REPLACE FUNCTION dml_utils_lib.mark_migration_run_cancelled(
    i_run_id bigint
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
BEGIN
    -- completed_at wins for a chunk that finished before the cancellation
    -- reached it. The coordinator invokes this through an autonomous background
    -- worker, so the marks survive a caller rollback.
    WITH cancelled_run AS (
        UPDATE dml_utils_data.migration_run
        SET cancelled_at = COALESCE(cancelled_at, pg_catalog.clock_timestamp())
        WHERE run_id = i_run_id
          AND completed_at IS NULL
        RETURNING run_id
        )
    UPDATE dml_utils_data.migration_boundary
    SET cancelled_at = COALESCE(cancelled_at, pg_catalog.clock_timestamp())
    WHERE run_id = i_run_id
      AND completed_at IS NULL
      AND cancelled_at IS NULL;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.mark_migration_run_cancelled IS
    'Marks a run and its unfinished, uncompleted boundaries cancelled. '
        'SECURITY DEFINER so the invoker-owned coordinator does not need metadata '
        'write privileges; callers run it in an autonomous background worker.';
