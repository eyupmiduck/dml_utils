DROP TRIGGER IF EXISTS migration_boundary_set_updated_at ON dml_utils.migration_boundary;
DROP TRIGGER IF EXISTS migration_run_set_updated_at ON dml_utils.migration_run;
DROP TABLE IF EXISTS dml_utils.migration_boundary;
DROP TABLE IF EXISTS dml_utils.migration_run;
