DROP TRIGGER IF EXISTS migration_run_set_updated_at ON dml_utils_data.migration_run;
DROP TRIGGER IF EXISTS migration_run_immutable ON dml_utils_data.migration_run;
DROP TRIGGER IF EXISTS migration_boundary_set_updated_at ON dml_utils_data.migration_boundary;
DROP TRIGGER IF EXISTS migration_boundary_immutable ON dml_utils_data.migration_boundary;
DROP TRIGGER IF EXISTS migration_error_set_updated_at ON dml_utils_data.migration_error;
