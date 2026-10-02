CREATE OR REPLACE TRIGGER migration_run_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

COMMENT ON TRIGGER migration_run_set_updated_at ON dml_utils_data.migration_run IS
    'Refreshes updated_at on every update of migration_run, regardless of the caller.';
