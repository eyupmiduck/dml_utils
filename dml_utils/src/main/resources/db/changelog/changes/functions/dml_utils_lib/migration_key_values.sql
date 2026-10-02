CREATE OR REPLACE FUNCTION dml_utils_lib.migration_key_values(
    i_key dml_utils_data.migration_key,
    i_key_kinds text[]
)
    RETURNS text[]
    LANGUAGE sql
    STABLE
    SECURITY INVOKER
AS
$$
-- Flatten a position-aligned migration_key back into one text value per
-- primary-key column, in key order, using i_key_kinds to select the array for
-- each position. The values are the text form the chunk predicate re-casts.
SELECT pg_catalog.array_agg(
               CASE i_key_kinds[position]
                   WHEN 'bigint' THEN (i_key).bigint_values[position]::text
                   WHEN 'text' THEN (i_key).text_values[position]
                   WHEN 'uuid' THEN (i_key).uuid_values[position]::text
               END
               ORDER BY position)
FROM pg_catalog.generate_subscripts(i_key_kinds, 1) AS position;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_key_values IS
    'Flattens a position-aligned migration_key into one text value per '
        'primary-key column, in key order, using the key kinds from '
        'primary_key_kinds.';
