-- Per-run throttle: wait this long after each successfully completed chunk
-- before the coordinator launches another. Stored on the run so a restart uses
-- the recorded value; set_migration_run_sleep_ms adjusts it between calls.
-- Use the lock-aware ddl_utils helper so this is safe on a live table.
SELECT ddl_utils.add_column(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_column_name => 'sleep_ms',
               i_column_type => 'integer',
               i_nullable => FALSE,
               i_default_value => '0');

COMMENT ON COLUMN dml_utils_data.migration_run.sleep_ms IS
    'Milliseconds to wait after each successfully completed chunk before '
        'launching another; 0 means no delay. Set when the run is created and '
        'changed for later calls with dml_utils.set_migration_run_sleep_ms.';

SELECT ddl_utils.add_check_constraint(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_constraint_name => 'migration_run_sleep_ms_check',
               i_check_expression => 'sleep_ms >= 0');
