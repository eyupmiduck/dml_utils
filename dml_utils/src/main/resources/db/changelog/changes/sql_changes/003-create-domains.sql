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

-- Packs a single primary-key value of any supported kind. Exactly one of the
-- three attributes is populated; migration_key_value enforces that.
CREATE TYPE dml_utils.migration_key AS (
    bigint_value bigint,
    text_value text,
    uuid_value uuid
);

COMMENT ON TYPE dml_utils.migration_key IS
    'Packed primary-key value for a chunk boundary; exactly one of bigint_value, '
        'text_value or uuid_value holds the key.';

-- Enforce the "exactly one attribute" invariant at the type level so every
-- boundary_id is a well-formed key.
CREATE DOMAIN dml_utils.migration_key_value AS dml_utils.migration_key
    CONSTRAINT migration_key_value_single_value_check CHECK (
        pg_catalog.num_nonnulls(
            (value).bigint_value, (value).text_value, (value).uuid_value
            ) = 1
        );

COMMENT ON DOMAIN dml_utils.migration_key_value IS
    'migration_key with exactly one populated attribute: a bigint, text or uuid '
        'primary-key value.';
