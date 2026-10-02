CREATE OR REPLACE FUNCTION dml_utils.set_migration_run_threads(
    i_label dml_utils_data.non_null_text,
    i_threads dml_utils_data.positive_integer
)
    RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- Only an unfinished run can have its thread count adjusted: a completed
    -- run's chunks are already processed.
    UPDATE dml_utils_data.migration_run
    SET threads = i_threads
    WHERE label = i_label
      AND archived_at IS NULL
      AND completed_at IS NULL;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no unfinished migration run for label %', i_label
            USING ERRCODE = 'P0002';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils.set_migration_run_threads IS
    'Replaces the recorded threads of the unfinished run for the label, so the '
        'next run_migration_chunks call uses the adjusted worker count.';
