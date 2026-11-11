CREATE OR REPLACE FUNCTION dml_utils.pause_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run_id bigint;
BEGIN
    -- Pause only an active, unfinished, not-cancelled run. The paused_at IS NULL
    -- guard makes a second pause idempotent: it must not move the timestamp.
    UPDATE dml_utils_data.migration_run
    SET paused_at = pg_catalog.clock_timestamp()
    WHERE label = i_label
      AND archived_at IS NULL
      AND completed_at IS NULL
      AND cancelled_at IS NULL
      AND paused_at IS NULL
    RETURNING run_id
        INTO l_run_id;

    IF l_run_id IS NOT NULL THEN
        RETURN l_run_id;
    END IF;

    -- Already paused: return the same run without changing the timestamp. A
    -- terminal or absent run returns NULL.
    SELECT run.run_id
    INTO l_run_id
    FROM dml_utils_data.migration_run AS run
    WHERE run.label = i_label
      AND run.archived_at IS NULL
      AND run.completed_at IS NULL
      AND run.cancelled_at IS NULL
      AND run.paused_at IS NOT NULL;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.pause_migration_run IS
    'Pauses the active (not archived, unfinished, not cancelled) migration run for '
        'the label, if any: sets paused_at so the coordinator stops launching new '
        'chunks and leaves the run resumable. A second pause returns the same run '
        'without moving the timestamp. Returns the paused run_id, or NULL when '
        'there was no active unfinished run. A paused run is not terminal; use '
        'resume_migration_run to continue or cancel_migration_run to stop it.';
