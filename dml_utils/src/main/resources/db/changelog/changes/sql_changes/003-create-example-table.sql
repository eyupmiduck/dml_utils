CREATE TABLE dml_utils.example
(
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.now(),
    updated_at timestamptz NOT NULL DEFAULT pg_catalog.now()
);

COMMENT ON TABLE dml_utils.example IS
    'Example table created and loaded by the dml_utils changelog.';
COMMENT ON COLUMN dml_utils.example.id IS
    'Surrogate primary key.';
COMMENT ON COLUMN dml_utils.example.name IS
    'Example payload.';
COMMENT ON COLUMN dml_utils.example.created_at IS
    'Row creation time.';
COMMENT ON COLUMN dml_utils.example.updated_at IS
    'Last update time, maintained by the set_updated_at() trigger.';

-- PostgreSQL has no CREATE TRIGGER IF NOT EXISTS, so drop first to keep the
-- changeset re-runnable against a partially seeded database.
DROP TRIGGER IF EXISTS example_set_updated_at ON dml_utils.example;
CREATE TRIGGER example_set_updated_at
    BEFORE UPDATE
    ON dml_utils.example
    FOR EACH ROW
EXECUTE FUNCTION dml_utils.set_updated_at();
