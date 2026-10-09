CREATE OR REPLACE FUNCTION dml_utils_data.reject_migration_run_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- label, chunk_size, chunk_by and the driving table are set when the run is
    -- created and must never change: together they identify the run and the
    -- boundaries computed from it. driving_table_relation_filepath is fixed at
    -- boundary-calculation time for a block run, so it is immutable too.
    IF NEW.label IS DISTINCT FROM OLD.label
        OR NEW.chunk_size IS DISTINCT FROM OLD.chunk_size
        OR NEW.chunk_by IS DISTINCT FROM OLD.chunk_by
        OR NEW.driving_table_schema_name IS DISTINCT FROM OLD.driving_table_schema_name
        OR NEW.driving_table_name IS DISTINCT FROM OLD.driving_table_name
        OR NEW.driving_table_relation_filepath IS DISTINCT FROM OLD.driving_table_relation_filepath
    THEN
        RAISE EXCEPTION 'migration_run label, chunk_size, chunk_by and driving table are immutable'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.reject_migration_run_update IS
    'BEFORE UPDATE trigger that rejects any change to migration_run.label, '
        'migration_run.chunk_size, migration_run.chunk_by, the driving table '
        'schema/name or the driving table relation filepath.';
