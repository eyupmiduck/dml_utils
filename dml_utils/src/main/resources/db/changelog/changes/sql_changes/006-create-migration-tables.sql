CREATE TABLE dml_utils_data.migration_run
(
    run_id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label                     text                            NOT NULL,
    sql_text                  text                            NOT NULL,
    chunk_size                integer                         NOT NULL,
    threads                   dml_utils_data.positive_integer NOT NULL,
    driving_table_schema_name dml_utils_data.non_null_text    NOT NULL,
    driving_table_name        dml_utils_data.non_null_text    NOT NULL,
    created_at                timestamptz                     NOT NULL DEFAULT pg_catalog.now(),
    updated_at                timestamptz                     NOT NULL DEFAULT pg_catalog.now(),
    completed_at              timestamptz,
    archived_at               timestamptz,
    CONSTRAINT migration_run_chunk_size_check CHECK (chunk_size > 0)
);

COMMENT ON TABLE dml_utils_data.migration_run IS
    'One row per boundary calculation; groups the boundaries of migration_boundary.';
COMMENT ON COLUMN dml_utils_data.migration_run.run_id IS
    'Surrogate primary key identifying the migration run.';
COMMENT ON COLUMN dml_utils_data.migration_run.label IS
    'Caller-supplied run label; unique among the runs that are not archived.';
COMMENT ON COLUMN dml_utils_data.migration_run.sql_text IS
    'The migration SQL recorded for the run; stored as given.';
COMMENT ON COLUMN dml_utils_data.migration_run.chunk_size IS
    'Number of source rows per chunk used to compute the boundaries.';
COMMENT ON COLUMN dml_utils_data.migration_run.threads IS
    'Number of pg_background workers used to process the run''s chunks; may be '
        'changed with dml_utils.set_migration_run_threads while the run is unfinished.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_schema_name IS
    'Schema of the driving table whose primary-key order defines the chunks.';
COMMENT ON COLUMN dml_utils_data.migration_run.driving_table_name IS
    'Driving table whose primary-key order defines the chunks.';
COMMENT ON COLUMN dml_utils_data.migration_run.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_run.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils_data.migration_run.completed_at IS
    'Set when the run finishes; NULL while the run is in progress.';
COMMENT ON COLUMN dml_utils_data.migration_run.archived_at IS
    'Set when the run is archived; NULL means the run is active.';

CREATE UNIQUE INDEX migration_run_label_active_idx
    ON dml_utils_data.migration_run (label)
    WHERE archived_at IS NULL;

COMMENT ON INDEX dml_utils_data.migration_run_label_active_idx IS
    'Ensures at most one active (not archived) run per label.';

CREATE TABLE dml_utils_data.migration_boundary
(
    run_id       bigint                       NOT NULL,
    boundary_no  bigint                       NOT NULL,
    boundary_id  dml_utils_data.migration_key NOT NULL,
    created_at   timestamptz                  NOT NULL DEFAULT pg_catalog.now(),
    updated_at   timestamptz                  NOT NULL DEFAULT pg_catalog.now(),
    completed_at timestamptz,
    PRIMARY KEY (run_id, boundary_no),
    CONSTRAINT migration_boundary_run_fk
        FOREIGN KEY (run_id)
            REFERENCES dml_utils_data.migration_run (run_id)
            ON DELETE CASCADE,
    -- The boundary key must be canonical; see
    -- dml_utils_lib.migration_key_is_canonical for the rule.
    CONSTRAINT migration_boundary_key_check CHECK (
        dml_utils_lib.migration_key_is_canonical(boundary_id)
        )
);

COMMENT ON TABLE dml_utils_data.migration_boundary IS
    'Precomputed fixed-row chunk boundaries: one starting boundary per chunk plus '
        'a final high-water boundary. A boundary is terminal when no following '
        'boundary exists for the run.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.run_id IS
    'Migration run this boundary belongs to.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.boundary_no IS
    'Boundary order within the run; 0 is the first chunk start.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.boundary_id IS
    'Packed primary-key value (position-aligned arrays in migration_key) of the '
        'first row in the chunk, or the captured maximum for the final high-water '
        'boundary.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils_data.migration_boundary.completed_at IS
    'Set when the chunk for this boundary is processed; NULL until then.';

CREATE TABLE dml_utils_data.migration_error
(
    error_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id      bigint      NOT NULL,
    boundary_no bigint      NOT NULL,
    sqlstate    text        NOT NULL,
    message     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at  timestamptz NOT NULL DEFAULT pg_catalog.now(),
    CONSTRAINT migration_error_boundary_fk
        FOREIGN KEY (run_id, boundary_no)
            REFERENCES dml_utils_data.migration_boundary (run_id, boundary_no)
            ON DELETE CASCADE
);

COMMENT ON TABLE dml_utils_data.migration_error IS
    'One row per failed chunk worker, recording the SQLSTATE and message so a '
        'run can be diagnosed without the worker logs.';
COMMENT ON COLUMN dml_utils_data.migration_error.error_id IS
    'Surrogate primary key identifying the error record.';
COMMENT ON COLUMN dml_utils_data.migration_error.run_id IS
    'Migration run whose chunk failed.';
COMMENT ON COLUMN dml_utils_data.migration_error.boundary_no IS
    'Boundary (chunk) of the run that failed.';
COMMENT ON COLUMN dml_utils_data.migration_error.sqlstate IS
    'SQLSTATE of the failure, as reported by the chunk worker.';
COMMENT ON COLUMN dml_utils_data.migration_error.message IS
    'Error message of the failure, as reported by the chunk worker.';
COMMENT ON COLUMN dml_utils_data.migration_error.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils_data.migration_error.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';

-- Index the referencing side of the FK so a boundary (or run) delete does not
-- scan migration_error for every cascaded row.
CREATE INDEX migration_error_boundary_idx
    ON dml_utils_data.migration_error (run_id, boundary_no);
