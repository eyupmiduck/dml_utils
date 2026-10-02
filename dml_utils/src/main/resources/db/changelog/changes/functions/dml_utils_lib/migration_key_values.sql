CREATE OR REPLACE FUNCTION dml_utils_lib.migration_key_values(
    i_key dml_utils_data.migration_key,
    i_key_kinds text[]
)
    RETURNS text[]
    LANGUAGE plpgsql
    IMMUTABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_values text[] := ARRAY []::text[];
    l_kind   text;
BEGIN
    -- Flatten a position-aligned migration_key back into one text value per
    -- primary-key column, in key order, using i_key_kinds to select the array
    -- for each position. The values are the text form the chunk predicate
    -- re-casts. Raise on an unknown kind rather than emitting NULL, which would
    -- make the caller's predicate match no rows.
    FOR l_position IN 1..pg_catalog.array_length(i_key_kinds, 1)
        LOOP
        l_kind := i_key_kinds[l_position];
        IF l_kind = 'bigint' THEN
            l_values := l_values || (i_key).bigint_values[l_position]::text;
        ELSIF l_kind = 'text' THEN
            l_values := l_values || (i_key).text_values[l_position];
        ELSIF l_kind = 'uuid' THEN
            l_values := l_values || (i_key).uuid_values[l_position]::text;
        ELSE
            RAISE EXCEPTION 'unsupported key kind %', l_kind
                USING ERRCODE = '22023';
        END IF;
    END LOOP;

    RETURN l_values;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_key_values IS
    'Flattens a position-aligned migration_key into one text value per '
        'primary-key column, in key order, using the key kinds from '
        'primary_key_kinds.';
