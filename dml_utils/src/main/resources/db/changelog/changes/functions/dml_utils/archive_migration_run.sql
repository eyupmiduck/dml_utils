CREATE OR REPLACE FUNCTION dml_utils.archive_migration_run(
    i_label dml_utils_data.non_null_text
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_run_id bigint;
BEGIN
    UPDATE dml_utils_data.migration_run
    SET archived_at = pg_catalog.now()
    WHERE label = i_label
      AND archived_at IS NULL
    RETURNING run_id
        INTO l_run_id;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.archive_migration_run IS
    'Archives the active (not archived) migration run for the label, if any, so '
        'the label can be reused; returns the archived run_id, or NULL when there '
        'was no active run.';
