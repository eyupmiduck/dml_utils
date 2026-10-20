SELECT ddl_utils.drop_constraint(
        i_schema_name => 'dml_utils_data',
        i_table_name => 'migration_run',
        i_constraint_name => 'migration_run_completed_or_cancelled_check');
SELECT ddl_utils.drop_constraint(
        i_schema_name => 'dml_utils_data',
        i_table_name => 'migration_run',
        i_constraint_name => 'migration_run_cancelled_at_check');
SELECT ddl_utils.drop_column(
        i_schema_name => 'dml_utils_data',
        i_table_name => 'migration_run',
        i_column_name => 'cancelled_at');
SELECT ddl_utils.drop_column(
        i_schema_name => 'dml_utils_data',
        i_table_name => 'migration_boundary',
        i_column_name => 'cancelled_at');
