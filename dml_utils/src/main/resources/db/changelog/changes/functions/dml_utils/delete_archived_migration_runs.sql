CREATE OR REPLACE FUNCTION dml_utils.delete_archived_migration_runs()
    RETURNS bigint
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
WITH deleted AS (
    DELETE FROM dml_utils_data.migration_run
        WHERE archived_at IS NOT NULL
        RETURNING 1)
SELECT pg_catalog.count(*)::bigint
FROM deleted;
$$;

COMMENT ON FUNCTION dml_utils.delete_archived_migration_runs() IS
    'Deletes every archived migration run (with its boundaries and errors), '
        'returning the number of runs deleted.';
