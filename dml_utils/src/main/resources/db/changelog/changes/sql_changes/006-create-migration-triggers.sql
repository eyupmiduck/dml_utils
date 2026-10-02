-- The migration tables' triggers. Kept separate from the tables so the tables
-- can be created before the routines that operate on them, and the triggers
-- (which call dml_utils_data.set_updated_at / reject_migration_*_update) run
-- after the functions that define those routines.
--
-- PostgreSQL has no CREATE TRIGGER IF NOT EXISTS, so drop first to keep the
-- changeset re-runnable against a partially seeded database.

-- Every table refreshes updated_at via the shared trigger.
DROP TRIGGER IF EXISTS migration_run_set_updated_at ON dml_utils_data.migration_run;
CREATE TRIGGER migration_run_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

-- label, chunk_size and the driving table are fixed at creation; reject any
-- attempt to change them.
DROP TRIGGER IF EXISTS migration_run_immutable ON dml_utils_data.migration_run;
CREATE TRIGGER migration_run_immutable
    BEFORE UPDATE
    ON dml_utils_data.migration_run
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.reject_migration_run_update();

DROP TRIGGER IF EXISTS migration_boundary_set_updated_at ON dml_utils_data.migration_boundary;
CREATE TRIGGER migration_boundary_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_boundary
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();

-- boundary_no and boundary_id are fixed when the boundaries are computed;
-- reject any attempt to change them.
DROP TRIGGER IF EXISTS migration_boundary_immutable ON dml_utils_data.migration_boundary;
CREATE TRIGGER migration_boundary_immutable
    BEFORE UPDATE
    ON dml_utils_data.migration_boundary
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.reject_migration_boundary_update();

DROP TRIGGER IF EXISTS migration_error_set_updated_at ON dml_utils_data.migration_error;
CREATE TRIGGER migration_error_set_updated_at
    BEFORE UPDATE
    ON dml_utils_data.migration_error
    FOR EACH ROW
EXECUTE FUNCTION dml_utils_data.set_updated_at();
