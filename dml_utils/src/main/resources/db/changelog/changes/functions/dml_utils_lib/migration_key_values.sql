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
    l_value  text;
BEGIN
    -- Flatten a position-aligned migration_key back into one text value per
    -- primary-key column, in key order, using i_key_kinds to select the array
    -- for each position. The values are the text form the chunk predicate
    -- re-casts. A NULL/empty kinds array would make the loop below iterate a
    -- NULL bound and silently return an empty result, so reject it explicitly;
    -- the migration_key type has one array per supported kind, so at most three.
    IF i_key_kinds IS NULL
        OR pg_catalog.cardinality(i_key_kinds) NOT BETWEEN 1 AND 3
    THEN
        RAISE EXCEPTION 'key kinds must contain one to three entries'
            USING ERRCODE = '22023';
    END IF;

    FOR l_position IN 1..pg_catalog.cardinality(i_key_kinds)
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would make every comparison below NULL (not true) and
            -- fall through to the ELSE branch; reject it explicitly.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            -- Exactly one value array is populated per position; an absent array
            -- or a NULL element at this position yields a NULL here, which keeps
            -- the result position-aligned with the key instead of shifting later
            -- values. render_chunk_sql rejects the NULL when it renders the
            -- predicate, so the hole is caught before any chunk runs.
            l_value := CASE l_kind
                           WHEN 'bigint' THEN (i_key).bigint_values[l_position]::text
                           WHEN 'text' THEN (i_key).text_values[l_position]
                           WHEN 'uuid' THEN (i_key).uuid_values[l_position]::text
                END;

            l_values := l_values || l_value;
        END LOOP;

    RETURN l_values;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.migration_key_values IS
    'Flattens a position-aligned migration_key into one text value per '
        'primary-key column, in key order, using the key kinds from '
        'primary_key_kinds.';
