-- Throwaway benchmark target table.
--
-- The default workload INSERTs each chunk's source rows here, so the source
-- table is never modified. The harness truncates it before every run. Run by
-- benchmark/setup.sh with :schema and :target_table set.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"target_table";

CREATE TABLE :"schema".:"target_table"
(
    id      bigint,
    payload text NOT NULL
);

COMMENT ON TABLE :"schema".:"target_table" IS
    'Benchmark fixture: throwaway destination the workload inserts into; truncated before each run.';
COMMENT ON COLUMN :"schema".:"target_table".id IS
    'Copied from the source row id.';
COMMENT ON COLUMN :"schema".:"target_table".payload IS
    'Copied from the source row payload.';
