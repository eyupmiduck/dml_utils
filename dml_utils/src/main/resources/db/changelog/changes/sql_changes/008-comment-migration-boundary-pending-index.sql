COMMENT ON INDEX dml_utils_data.migration_boundary_pending_idx IS
    'Supports the coordinator''s next-chunk lookup (run_migration_chunks): the '
        'partial predicate keeps only pending boundaries, so the first unclaimed '
        'boundary is found without scanning the completed prefix on every launch.';
