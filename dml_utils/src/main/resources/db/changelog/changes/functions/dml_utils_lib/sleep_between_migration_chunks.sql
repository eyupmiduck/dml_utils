CREATE OR REPLACE FUNCTION dml_utils_lib.sleep_between_migration_chunks(
    i_run_id bigint,
    i_sleep_ms integer
)
    RETURNS TABLE
            (
                o_cancelled_at timestamptz,
                o_paused_at    timestamptz
            )
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_sleep_remaining_ms integer := i_sleep_ms;
    l_sleep_slice_ms     integer;
    l_cancelled_at       timestamptz;
    l_paused_at          timestamptz;
BEGIN
    -- Slice the sleep so cancellation and pause are noticed promptly. sleep_ms
    -- itself is not re-read here: it is restart-only adjustment, stored with the
    -- run.
    WHILE l_sleep_remaining_ms > 0
        LOOP
            l_sleep_slice_ms := least(l_sleep_remaining_ms, 100);
            PERFORM pg_catalog.pg_sleep(l_sleep_slice_ms::double precision / 1000.0);
            l_sleep_remaining_ms := l_sleep_remaining_ms - l_sleep_slice_ms;

            SELECT run.cancelled_at, run.paused_at
            INTO l_cancelled_at, l_paused_at
            FROM dml_utils_data.migration_run AS run
            WHERE run.run_id = i_run_id;
            EXIT WHEN l_cancelled_at IS NOT NULL OR l_paused_at IS NOT NULL;
        END LOOP;

    o_cancelled_at := l_cancelled_at;
    o_paused_at := l_paused_at;
    RETURN NEXT;
    RETURN;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.sleep_between_migration_chunks(
    bigint,
    integer
    ) IS
    'Sleeps the run''s per-chunk delay in 100ms slices and returns the run''s '
        'cancelled_at and paused_at if either was noticed, so the coordinator can '
        'stop launching promptly.';
