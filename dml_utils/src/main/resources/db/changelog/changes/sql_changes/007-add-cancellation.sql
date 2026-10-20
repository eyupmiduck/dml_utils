-- Add the cancellation timestamps with the lock-aware ddl_utils helper. dml_utils
-- assumes ddl_utils is installed in the same database, so the columns are added
-- under a bounded lock rather than an unbounded ACCESS EXCLUSIVE lock.
SELECT ddl_utils.add_column(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_column_name => 'cancelled_at',
               i_column_type => 'timestamptz',
               i_nullable => TRUE);
SELECT ddl_utils.add_column(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_boundary',
               i_column_name => 'cancelled_at',
               i_column_type => 'timestamptz',
               i_nullable => TRUE);

COMMENT ON COLUMN dml_utils_data.migration_run.cancelled_at IS
    'Actual server time when the run was cancelled; NULL unless cancelled. A '
        'cancelled run is terminal: archive it and create a new run to resume.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.cancelled_at IS
    'Actual server time when the boundary was cancelled; NULL unless the run was '
        'cancelled before this chunk completed. A boundary with completed_at set '
        'is authoritative even if cancelled_at is also set.';

-- The lock-aware helper emits these as NOT VALID, so they are enforced for new
-- and updated rows without a validating scan of the table.
SELECT ddl_utils.add_check_constraint(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_constraint_name => 'migration_run_cancelled_at_check',
               i_check_expression => 'cancelled_at IS NULL OR (started_at IS NOT NULL AND cancelled_at >= started_at)');
SELECT ddl_utils.add_check_constraint(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_constraint_name => 'migration_run_completed_or_cancelled_check',
               i_check_expression => 'completed_at IS NULL OR cancelled_at IS NULL');
