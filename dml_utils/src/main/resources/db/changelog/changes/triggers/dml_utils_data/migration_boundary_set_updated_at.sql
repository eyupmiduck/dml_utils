CREATE OR REPLACE TRIGGER migration_boundary_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_boundary
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

COMMENT ON TRIGGER migration_boundary_set_updated_at ON dml_utils_data.migration_boundary IS
    'Refreshes updated_at on every update of migration_boundary, regardless of the caller.';
