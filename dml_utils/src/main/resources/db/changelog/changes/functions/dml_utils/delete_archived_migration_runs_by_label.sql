CREATE OR REPLACE FUNCTION dml_utils.delete_archived_migration_runs(
    i_label dml_utils_data.non_null_text
)
    RETURNS bigint
    LANGUAGE sql
    VOLATILE
    SECURITY INVOKER
AS
$$
WITH deleted AS (
    DELETE FROM dml_utils_data.migration_run
    WHERE archived_at IS NOT NULL
      AND label = i_label
    RETURNING 1
    )
SELECT pg_catalog.count(*)::bigint
FROM deleted;
$$;

COMMENT ON FUNCTION dml_utils.delete_archived_migration_runs(dml_utils_data.non_null_text) IS
    'Deletes every archived migration run for the label (with its boundaries and '
        'errors), returning the number of runs deleted.';
