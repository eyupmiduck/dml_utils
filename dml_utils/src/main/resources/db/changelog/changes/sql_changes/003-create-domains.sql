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
