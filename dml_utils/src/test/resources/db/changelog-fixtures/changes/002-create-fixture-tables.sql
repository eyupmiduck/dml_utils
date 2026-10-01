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
