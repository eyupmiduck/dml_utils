CREATE OR REPLACE FUNCTION dml_utils.resume_migration_run(
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
    -- Clearing paused_at resumes the run. The paused_at IS NOT NULL guard keeps
    -- this idempotent and avoids touching a run that is not paused.
    UPDATE dml_utils_data.migration_run
    SET paused_at = NULL
    WHERE label = i_label
      AND archived_at IS NULL
      AND completed_at IS NULL
      AND cancelled_at IS NULL
      AND paused_at IS NOT NULL
    RETURNING run_id
        INTO l_run_id;

    IF l_run_id IS NOT NULL THEN
        RETURN l_run_id;
    END IF;

    -- Resuming an active unfinished run that is not paused is a no-op, so return
    -- its id to keep callers idempotent. A terminal or absent run returns NULL.
    SELECT run.run_id
    INTO l_run_id
    FROM dml_utils_data.migration_run AS run
    WHERE run.label = i_label
      AND run.archived_at IS NULL
      AND run.completed_at IS NULL
      AND run.cancelled_at IS NULL;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.resume_migration_run IS
    'Resumes the active (not archived, unfinished, not cancelled) migration run '
        'for the label, if any: clears paused_at so the next run_migration_chunks '
        'call continues at the first pending boundary. Resuming a run that is not '
        'paused is a no-op. Returns the run_id, or NULL when there was no active '
        'unfinished run.';
