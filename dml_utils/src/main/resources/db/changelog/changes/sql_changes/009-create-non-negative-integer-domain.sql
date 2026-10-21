CREATE DOMAIN dml_utils_data.non_negative_integer AS integer
    CONSTRAINT non_negative_integer_check CHECK (value IS NOT NULL AND value >= 0);

COMMENT ON DOMAIN dml_utils_data.non_negative_integer IS
    'integer that is NOT NULL and greater than or equal to 0; used for the '
        'per-chunk sleep so the no-delay value 0 is valid.';
