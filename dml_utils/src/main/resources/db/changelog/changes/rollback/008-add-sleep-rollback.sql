SELECT ddl_utils.drop_constraint(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_constraint_name => 'migration_run_sleep_ms_check');
SELECT ddl_utils.drop_column(
               i_schema_name => 'dml_utils_data',
               i_table_name => 'migration_run',
               i_column_name => 'sleep_ms');
