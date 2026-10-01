CREATE TABLE dml_utils.migration_run
(
    run_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label        text        NOT NULL,
    sql_text     text        NOT NULL,
    chunk_size   integer     NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at   timestamptz NOT NULL DEFAULT pg_catalog.now(),
    completed_at timestamptz,
    archived_at  timestamptz
);

COMMENT ON TABLE dml_utils.migration_run IS
    'One row per boundary calculation; groups the boundaries of migration_boundary.';
COMMENT ON COLUMN dml_utils.migration_run.run_id IS
    'Surrogate primary key identifying the migration run.';
COMMENT ON COLUMN dml_utils.migration_run.label IS
    'Caller-supplied run label; unique among the runs that are not archived.';
COMMENT ON COLUMN dml_utils.migration_run.sql_text IS
    'The migration SQL recorded for the run; stored as given.';
COMMENT ON COLUMN dml_utils.migration_run.chunk_size IS
    'Number of source rows per chunk used to compute the boundaries.';
COMMENT ON COLUMN dml_utils.migration_run.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils.migration_run.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils.migration_run.completed_at IS
    'Set when the run finishes; NULL while the run is in progress.';
COMMENT ON COLUMN dml_utils.migration_run.archived_at IS
    'Set when the run is archived; NULL means the run is active.';

CREATE UNIQUE INDEX migration_run_label_active_idx
    ON dml_utils.migration_run (label)
    WHERE archived_at IS NULL;

COMMENT ON INDEX dml_utils.migration_run_label_active_idx IS
    'Ensures at most one active (not archived) run per label.';

-- PostgreSQL has no CREATE TRIGGER IF NOT EXISTS, so drop first to keep the
-- changeset re-runnable against a partially seeded database.
DROP TRIGGER IF EXISTS migration_run_set_updated_at ON dml_utils.migration_run;
CREATE TRIGGER migration_run_set_updated_at
    BEFORE UPDATE
    ON dml_utils.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils.set_updated_at();

CREATE TABLE dml_utils.migration_boundary
(
    run_id       bigint      NOT NULL REFERENCES dml_utils.migration_run (run_id) ON DELETE CASCADE,
    boundary_no  bigint      NOT NULL,
    boundary_id  bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at   timestamptz NOT NULL DEFAULT pg_catalog.now(),
    completed_at timestamptz,
    PRIMARY KEY (run_id, boundary_no)
);

COMMENT ON TABLE dml_utils.migration_boundary IS
    'Precomputed fixed-row chunk boundaries: one starting boundary per chunk plus '
        'a final high-water boundary. A boundary is terminal when no following '
        'boundary exists for the run.';
COMMENT ON COLUMN dml_utils.migration_boundary.run_id IS
    'Migration run this boundary belongs to.';
COMMENT ON COLUMN dml_utils.migration_boundary.boundary_no IS
    'Boundary order within the run; 0 is the first chunk start.';
COMMENT ON COLUMN dml_utils.migration_boundary.boundary_id IS
    'Primary-key value of the first row in the chunk, or the captured maximum '
        'for the final high-water boundary.';
COMMENT ON COLUMN dml_utils.migration_boundary.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils.migration_boundary.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';
COMMENT ON COLUMN dml_utils.migration_boundary.completed_at IS
    'Set when the chunk for this boundary is processed; NULL until then.';

CREATE INDEX migration_boundary_id_idx
    ON dml_utils.migration_boundary (run_id, boundary_id);

DROP TRIGGER IF EXISTS migration_boundary_set_updated_at ON dml_utils.migration_boundary;
CREATE TRIGGER migration_boundary_set_updated_at
    BEFORE UPDATE
    ON dml_utils.migration_boundary
    FOR EACH ROW
EXECUTE FUNCTION dml_utils.set_updated_at();
