CREATE DOMAIN dml_utils_data.positive_integer AS integer
    CONSTRAINT positive_integer_check CHECK (value IS NOT NULL AND value > 0);

COMMENT ON DOMAIN dml_utils_data.positive_integer IS
    'integer that is NOT NULL and greater than 0.';

-- Trim ASCII whitespace, including vertical tab via the octal escape \013:
-- PostgreSQL has no \v escape in E-strings (it reads \v as the letter v).
CREATE DOMAIN dml_utils_data.non_null_text AS text
    CONSTRAINT non_null_text_check CHECK (
        value IS NOT NULL AND pg_catalog.btrim(value, E' \t\n\r\f\013') <> ''
        );

COMMENT ON DOMAIN dml_utils_data.non_null_text IS
    'text that is NOT NULL and not blank.';

-- Packs the primary-key value of a chunk boundary, for a primary key of one to
-- three columns. Each array is position-aligned to the primary-key columns: at
-- index i, the array matching that column's kind holds its value and the other
-- arrays hold NULL (or are NULL when no column of that kind is present). The
-- arrays therefore all have the same length as the primary key has columns, and
-- exactly one array is non-NULL per key. The invariants are enforced by the
-- migration_boundary check constraint; the type is not wrapped in a domain
-- because a domain over a UDT makes jOOQ generate a static-init cycle for the
-- schema class.
-- SQLFluff parses a composite type's attributes as bare words, so the
-- layout:type:data_type alignment relaxation (see .sqlfluff) does not apply
-- here and IntelliJ's aligned columns would trip LT01.
-- noqa:disable=LT01
CREATE TYPE dml_utils_data.migration_key AS
(
    bigint_values bigint[],
    text_values   text[],
    uuid_values   uuid[]
);
-- noqa:enable=LT01

COMMENT ON TYPE dml_utils_data.migration_key IS
    'Packed primary-key value for a chunk boundary: position-aligned arrays, one '
        'per supported kind, exactly one of which is non-NULL. Index i holds the '
        'value of primary-key column i when that column has the array''s kind.';
