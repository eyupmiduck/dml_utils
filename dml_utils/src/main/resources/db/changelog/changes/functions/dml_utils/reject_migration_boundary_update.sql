CREATE OR REPLACE FUNCTION dml_utils.reject_migration_boundary_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- boundary_no and boundary_id are set when the boundaries are computed and
    -- must never change: they define the chunk ranges.
    IF NEW.boundary_no IS DISTINCT FROM OLD.boundary_no
        OR NEW.boundary_id IS DISTINCT FROM OLD.boundary_id
    THEN
        RAISE EXCEPTION 'migration_boundary.boundary_no and boundary_id are immutable'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils.reject_migration_boundary_update IS
    'BEFORE UPDATE trigger that rejects any change to '
        'migration_boundary.boundary_no or migration_boundary.boundary_id.';
