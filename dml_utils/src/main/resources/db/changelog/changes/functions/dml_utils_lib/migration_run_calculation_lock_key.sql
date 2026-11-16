CREATE OR REPLACE FUNCTION dml_utils_lib.migration_run_calculation_lock_key(
    i_run_id bigint
)
    RETURNS bigint
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
    SECURITY INVOKER
AS
$$
    -- A single bigint advisory-lock key, derived from the run id. The prefix keeps
    -- the namespace distinct from any other advisory lock a caller may take.
SELECT pg_catalog.hashtextextended(
               pg_catalog.format('dml_utils.migration_run.calculation.%s', i_run_id), 0);
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_run_calculation_lock_key(bigint) IS
    'Returns the advisory-lock key used to serialize partition-wise boundary '
        'calculation for a run. IMMUTABLE: pg_catalog.format and '
        'pg_catalog.hashtextextended are both immutable, so the key is stable for '
        'the same run id. STRICT: a NULL run id yields NULL instead of collapsing '
        'every NULL caller onto one shared key. PARALLEL SAFE: it reads no state '
        'and takes no lock.';
