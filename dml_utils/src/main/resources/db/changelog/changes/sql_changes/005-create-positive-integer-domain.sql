CREATE DOMAIN dml_utils.positive_integer AS integer
    CONSTRAINT positive_integer_check CHECK (value IS NOT NULL AND value > 0);

COMMENT ON DOMAIN dml_utils.positive_integer IS
    'integer that is NOT NULL and greater than 0.';
