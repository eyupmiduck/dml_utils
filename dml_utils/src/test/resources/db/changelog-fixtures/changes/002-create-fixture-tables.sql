CREATE TABLE dml_utils_fixtures.test_bigint
(
    id      bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_bigint IS
    'Fixture: a table with a single bigint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_bigint.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_bigint.payload IS
    'Fixture payload column.';

-- A second bigint-keyed table, for tests that must tell two driving tables
-- apart (for example resuming a run against a different table).
CREATE TABLE dml_utils_fixtures.test_other
(
    id      bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_other IS
    'Fixture: a second table with a single bigint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_other.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_other.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_integer
(
    id      integer PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_integer IS
    'Fixture: a table with a single integer primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_integer.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_integer.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_smallint
(
    id      smallint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_smallint IS
    'Fixture: a table with a single smallint primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_smallint.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_smallint.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_text
(
    id      text PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_text IS
    'Fixture: a table with a single text primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_text.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_text.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_uuid
(
    id      uuid PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_uuid IS
    'Fixture: a table with a single uuid primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_uuid.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_uuid.payload IS
    'Fixture payload column.';

-- The primary-key column is not named id, to prove the routines read the name
-- from the catalog.
CREATE TABLE dml_utils_fixtures.test_key
(
    key     bigint PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_key IS
    'Fixture: a table whose primary key is named key, not id.';
COMMENT ON COLUMN dml_utils_fixtures.test_key.key IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_key.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_no_pk
(
    id      bigint,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_no_pk IS
    'Fixture: a table without a primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_no_pk.id IS
    'Fixture id column.';
COMMENT ON COLUMN dml_utils_fixtures.test_no_pk.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_composite_pk
(
    a       bigint,
    b       bigint,
    payload text,
    PRIMARY KEY (a, b)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_pk IS
    'Fixture: a table with a composite primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.a IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.b IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_pk.payload IS
    'Fixture payload column.';

-- A two-column, mixed-kind primary key (bigint + text), to exercise a
-- heterogeneous key at the shortest composite arity.
CREATE TABLE dml_utils_fixtures.test_composite_mixed
(
    a       bigint,
    b       text,
    payload text,
    PRIMARY KEY (a, b)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_mixed IS
    'Fixture: a table with a two-column, mixed-kind primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.a IS
    'Fixture first primary-key column (bigint).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.b IS
    'Fixture second primary-key column (text).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_mixed.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_numeric
(
    id      numeric PRIMARY KEY,
    payload text
);

COMMENT ON TABLE dml_utils_fixtures.test_numeric IS
    'Fixture: a table with a numeric primary key, which is not a supported key type.';
COMMENT ON COLUMN dml_utils_fixtures.test_numeric.id IS
    'Fixture primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_numeric.payload IS
    'Fixture payload column.';

-- A three-column primary key that mixes key kinds (integer, text, uuid), to
-- prove multi-column keys and heterogeneous kinds both work. The physical
-- column order (a, b, c) differs from the key order (b, a, c), so a routine
-- that reads column order instead of the index order would return the wrong
-- sequence; the key order is b (integer), a (text), c (uuid).
CREATE TABLE dml_utils_fixtures.test_composite_three
(
    a       text    NOT NULL,
    b       integer NOT NULL,
    c       uuid    NOT NULL,
    payload text,
    PRIMARY KEY (b, a, c)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_three IS
    'Fixture: a table with a three-column, mixed-kind primary key.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.b IS
    'Fixture first primary-key column (integer).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.a IS
    'Fixture second primary-key column (text).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.c IS
    'Fixture third primary-key column (uuid).';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_three.payload IS
    'Fixture payload column.';

-- A two-column key whose second column is literally named id1, so the top-level
-- ORDER BY in populate_migration_boundaries would resolve the output alias id1
-- (the first key column) instead of the input column if the terminal scan were
-- built from raw column names rather than the idN aliases.
CREATE TABLE dml_utils_fixtures.test_composite_id1
(
    x       bigint,
    id1     bigint,
    payload text,
    PRIMARY KEY (x, id1)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_id1 IS
    'Fixture: a two-column key whose second column is named id1, to catch alias/column ORDER BY collisions.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.x IS
    'Fixture first primary-key column.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.id1 IS
    'Fixture second primary-key column, deliberately named id1.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_id1.payload IS
    'Fixture payload column.';

-- Four primary-key columns, one more than the supported maximum.
CREATE TABLE dml_utils_fixtures.test_composite_four
(
    a       bigint,
    b       bigint,
    c       bigint,
    d       bigint,
    payload text,
    PRIMARY KEY (a, b, c, d)
);

COMMENT ON TABLE dml_utils_fixtures.test_composite_four IS
    'Fixture: a table with four primary-key columns, more than the supported maximum.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.a IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.b IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.c IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.d IS
    'Fixture primary-key part.';
COMMENT ON COLUMN dml_utils_fixtures.test_composite_four.payload IS
    'Fixture payload column.';

-- A partitioned table, to prove block chunking rejects a relation that has no
-- single physical heap (ctid is per-partition, and the parent has no storage).
CREATE TABLE dml_utils_fixtures.test_partitioned
(
    id      bigint NOT NULL,
    payload text
) PARTITION BY RANGE (id);

COMMENT ON TABLE dml_utils_fixtures.test_partitioned IS
    'Fixture: a partitioned table, which block chunking must reject.';
COMMENT ON COLUMN dml_utils_fixtures.test_partitioned.id IS
    'Fixture partition key.';
COMMENT ON COLUMN dml_utils_fixtures.test_partitioned.payload IS
    'Fixture payload column.';

CREATE TABLE dml_utils_fixtures.test_partitioned_p1 PARTITION OF dml_utils_fixtures.test_partitioned
    FOR VALUES FROM (0) TO (1000);

COMMENT ON TABLE dml_utils_fixtures.test_partitioned_p1 IS
    'Fixture: the first partition of test_partitioned.';
