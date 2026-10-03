CREATE OR REPLACE FUNCTION dml_utils_data.reject_migration_boundary_update()
    RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
BEGIN
    -- run_id, boundary_no and boundary_id identify the boundary and the run whose
    -- driving table, key ordering and chunk ranges it belongs to. They are set
    -- when the boundaries are computed and must never change; moving a boundary
    -- to another run would let that run process values computed for a different
    -- migration.
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
        OR NEW.boundary_no IS DISTINCT FROM OLD.boundary_no
        OR NEW.boundary_id IS DISTINCT FROM OLD.boundary_id
    THEN
        RAISE EXCEPTION 'migration_boundary.run_id, boundary_no and boundary_id are immutable'
            USING ERRCODE = '22023';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION dml_utils_data.reject_migration_boundary_update IS
    'BEFORE UPDATE trigger that rejects any change to '
        'migration_boundary.run_id, boundary_no or boundary_id.';
