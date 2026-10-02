CREATE OR REPLACE TRIGGER migration_error_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_error
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

COMMENT ON TRIGGER migration_error_set_updated_at ON dml_utils_data.migration_error IS
    'Refreshes updated_at on every update of migration_error, regardless of the caller.';
