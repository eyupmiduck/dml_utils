-- Deterministic benchmark source table.
--
-- Created outside the Liquibase-managed schemas: it is a benchmark fixture, not
-- application schema, so it carries only the columns the workload reads. Run by
-- benchmark/setup.sh with :schema, :source_table and :rows set.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS :"schema";

DROP TABLE IF EXISTS :"schema".:"source_table";

CREATE TABLE :"schema".:"source_table"
(
    id
    bigint
    PRIMARY
    KEY,
    payload
    text
    NOT
    NULL
);

COMMENT ON TABLE :"schema".:"source_table" IS
    'Benchmark fixture: deterministic synthetic rows keyed by a bigint primary key.';
COMMENT ON COLUMN :"schema".:"source_table".id IS
    'Synthetic primary key, 1..rows.';
COMMENT ON COLUMN :"schema".:"source_table".payload IS
    'Deterministic payload derived from id.';

-- The same id always yields the same payload, so refreshing the database
-- reproduces the table exactly.
INSERT INTO :"schema".:"source_table" (id, payload)
SELECT g,
       pg_catalog.md5(g::text)
FROM pg_catalog.generate_series(1, :rows) AS g;
