CREATE DOMAIN dml_utils.positive_integer AS integer
    CONSTRAINT positive_integer_check CHECK (value IS NOT NULL AND value > 0);

COMMENT ON DOMAIN dml_utils.positive_integer IS
    'integer that is NOT NULL and greater than 0.';

-- Trim ASCII whitespace, including vertical tab via the octal escape \013:
-- PostgreSQL has no \v escape in E-strings (it reads \v as the letter v).
CREATE DOMAIN dml_utils.non_null_text AS text
    CONSTRAINT non_null_text_check CHECK (
        value IS NOT NULL AND pg_catalog.btrim(value, E' \t\n\r\f\013') <> ''
        );

COMMENT ON DOMAIN dml_utils.non_null_text IS
    'text that is NOT NULL and not blank.';

-- Packs a single primary-key value of any supported kind into one column. The
-- "exactly one attribute" invariant is enforced by the migration_boundary
-- check constraint; the type is not wrapped in a domain because a domain over
-- a UDT makes jOOQ generate a static-init cycle for the schema class.
-- SQLFluff parses a composite type's attributes as bare words, so the
-- layout:type:data_type alignment relaxation (see .sqlfluff) does not apply
-- here and IntelliJ's aligned columns would trip LT01.
-- noqa:disable=LT01
CREATE TYPE dml_utils.migration_key AS
(
    bigint_value bigint,
    text_value   text,
    uuid_value   uuid
);
-- noqa:enable=LT01

COMMENT ON TYPE dml_utils.migration_key IS
    'Packed primary-key value for a chunk boundary; exactly one of bigint_value, '
        'text_value or uuid_value holds the key.';
