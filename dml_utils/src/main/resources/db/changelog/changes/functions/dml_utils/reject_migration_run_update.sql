CREATE OR REPLACE FUNCTION dml_utils.reject_migration_run_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- label, chunk_size and the driving table are set when the run is created
    -- and must never change: together they identify the run and the boundaries
    -- computed from it.
    IF NEW.label IS DISTINCT FROM OLD.label
        OR NEW.chunk_size IS DISTINCT FROM OLD.chunk_size
        OR NEW.driving_table_schema_name IS DISTINCT FROM OLD.driving_table_schema_name
        OR NEW.driving_table_name IS DISTINCT FROM OLD.driving_table_name
    THEN
        RAISE EXCEPTION 'migration_run label, chunk_size and driving table are immutable'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils.reject_migration_run_update IS
    'BEFORE UPDATE trigger that rejects any change to migration_run.label, '
        'migration_run.chunk_size or the driving table schema/name.';
