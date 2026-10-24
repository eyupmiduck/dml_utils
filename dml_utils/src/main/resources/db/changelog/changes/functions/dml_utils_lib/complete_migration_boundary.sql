CREATE OR REPLACE FUNCTION dml_utils_lib.complete_migration_boundary(
    i_run_id bigint,
    i_boundary_no bigint,
    i_started_at timestamptz
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_updated integer;
BEGIN
    UPDATE dml_utils_data.migration_boundary
    SET started_at   = i_started_at,
        completed_at = pg_catalog.clock_timestamp()
    WHERE run_id = i_run_id
      AND boundary_no = i_boundary_no;

    GET DIAGNOSTICS l_updated = ROW_COUNT;
    IF l_updated = 0 THEN
        RAISE EXCEPTION 'migration boundary % for run % was not found while completing it',
            i_boundary_no, i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- Keep the counter in the same transaction as the boundary completion so it
    -- cannot drift from the completed boundaries.
    UPDATE dml_utils_data.migration_run
    SET total_chunks_completed = total_chunks_completed + 1
    WHERE run_id = i_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.complete_migration_boundary IS
    'Records started_at/completed_at for one claimed boundary and advances the '
        'run''s completed-chunk counter. SECURITY DEFINER because the caller-owned '
        'chunk worker intentionally has no metadata write privileges.';
