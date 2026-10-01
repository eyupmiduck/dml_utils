CREATE OR REPLACE FUNCTION dml_utils.reject_migration_run_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- label and chunk_size are set when the run is created and must never
    -- change: they identify the run and the boundaries computed from it.
    IF NEW.label IS DISTINCT FROM OLD.label
        OR NEW.chunk_size IS DISTINCT FROM OLD.chunk_size
    THEN
        RAISE EXCEPTION 'migration_run.label and chunk_size are immutable'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils.reject_migration_run_update IS
    'BEFORE UPDATE trigger that rejects any change to migration_run.label or '
        'migration_run.chunk_size.';
