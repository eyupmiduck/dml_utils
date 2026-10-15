CREATE INDEX CONCURRENTLY migration_boundary_pending_idx
    ON dml_utils_data.migration_boundary (run_id, boundary_no)
    WHERE completed_at IS NULL;
