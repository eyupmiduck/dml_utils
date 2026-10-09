CREATE OR REPLACE FUNCTION dml_utils_lib.render_chunk_sql(
    i_sql_text dml_utils_data.non_null_text,
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_table_alias dml_utils_data.non_null_text,
    i_primary_key_columns name[],
    i_key_kinds text[],
    i_start_values text[],
    i_end_values text[],
    i_is_final boolean
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    -- Control-character sentinels that stand in for one token each while the
    -- other token is being replaced, so a substituted value can never be
    -- re-scanned or rewritten.
    l_driving_table_sentinel   constant text    := pg_catalog.chr(1);
    l_chunking_clause_sentinel constant text    := pg_catalog.chr(2);
    l_driving_table                     text;
    l_column_count                      integer;
    l_column_names                      text[];
    l_start_literals                    text[];
    l_end_literals                      text[];
    l_boxes                             text[];
    l_box                               text;
    l_chunking_clause                   text;
    l_rendered                          text;
    l_kind                              text;
    l_collation                         text;
    l_start_less_than_end               boolean;
    l_start_bigint                      bigint;
    l_end_bigint                        bigint;
    l_start_uuid                        uuid;
    l_end_uuid                          uuid;
    -- Direction of the first differing column (-1 start < end, 1 start > end,
    -- 0 all equal) and its position, which selects the box decomposition.
    l_first_difference                  integer := 0;
    l_first_difference_position         integer := 0;
BEGIN
    PERFORM dml_utils_lib.assert_chunking_template(i_sql_text => i_sql_text);

    -- i_is_final selects the upper bound (inclusive for the final chunk, exclusive
    -- otherwise); a NULL would silently take the non-final branch, so reject it.
    IF i_is_final IS NULL THEN
        RAISE EXCEPTION 'i_is_final must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    -- All four arrays are indexed from 1 below, so they must be one-dimensional
    -- with a lower bound of 1 and the same non-zero length. A different lower
    -- bound (or a multidimensional array) would make the loop read the wrong
    -- element or a NULL; array_length of an empty/NULL array is NULL, which the
    -- length comparisons below reject.
    IF pg_catalog.array_ndims(i_primary_key_columns) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_primary_key_columns, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_key_kinds) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_key_kinds, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_start_values) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_start_values, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_ndims(i_end_values) IS DISTINCT FROM 1
        OR pg_catalog.array_lower(i_end_values, 1) IS DISTINCT FROM 1
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS NULL
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_key_kinds, 1)
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_start_values, 1)
        OR pg_catalog.array_length(i_primary_key_columns, 1) IS DISTINCT FROM pg_catalog.array_length(i_end_values, 1)
    THEN
        RAISE EXCEPTION 'primary key columns, kinds and boundary values must be one-dimensional, 1-based, same-length and non-empty'
            USING ERRCODE = '22023';
    END IF;

    -- Validate each column and build its alias-qualified reference and its two
    -- cast literals. The kind selects the explicit cast and is interpolated into
    -- the SQL, so it must be one of the known kinds (never caller SQL); the values
    -- are quoted with %L.
    l_column_count := pg_catalog.array_length(i_primary_key_columns, 1);
    l_column_names := ARRAY []::text[];
    l_start_literals := ARRAY []::text[];
    l_end_literals := ARRAY []::text[];
    FOR l_position IN 1..l_column_count
        LOOP
            l_kind := i_key_kinds[l_position];

            -- A NULL kind would slip past NOT IN (NULL NOT IN (...) is NULL,
            -- not true), so guard it explicitly.
            IF l_kind IS NULL OR l_kind NOT IN ('bigint', 'text', 'uuid') THEN
                RAISE EXCEPTION 'unsupported key kind %', l_kind
                    USING ERRCODE = '22023';
            END IF;

            -- A NULL or empty column name would render a degenerate %I token and
            -- only fail later when a worker parses the chunk SQL; reject it here.
            IF i_primary_key_columns[l_position] IS NULL
                OR i_primary_key_columns[l_position] = ''
            THEN
                RAISE EXCEPTION 'primary-key column name must not be NULL or empty at position %',
                    l_position
                    USING ERRCODE = '22023';
            END IF;

            -- %L renders a NULL as an unquoted NULL, so a missing boundary value
            -- would render a predicate that silently matches no rows (or narrows a
            -- partial row) while the chunk is still marked complete; fail loudly.
            IF i_start_values[l_position] IS NULL OR i_end_values[l_position] IS NULL THEN
                RAISE EXCEPTION 'boundary % value for primary-key column % must not be NULL',
                    CASE WHEN i_start_values[l_position] IS NULL THEN 'start' ELSE 'end' END,
                    i_primary_key_columns[l_position]
                    USING ERRCODE = '22023';
            END IF;

            -- A non-NULL value that is not a valid literal for its kind would be
            -- emitted into the chunk SQL and only fail when a worker parses it;
            -- validate by casting here (and keep the typed value for the range
            -- check below), so the failure is not deferred. text accepts any
            -- value, so only bigint and uuid need a cast (literals, not
            -- pg_input_is_valid, whose type-name argument PL/pgSQL caches per
            -- expression and which therefore misfires in this loop). Catch only
            -- the invalid-text conditions (22023 / 22P02); an unexpected error is
            -- re-raised with its original SQLSTATE.
            BEGIN
                IF l_kind = 'bigint' THEN
                    l_start_bigint := i_start_values[l_position]::bigint;
                    l_end_bigint := i_end_values[l_position]::bigint;
                    IF l_first_difference = 0 AND l_start_bigint IS DISTINCT FROM l_end_bigint THEN
                        l_first_difference := CASE WHEN l_start_bigint < l_end_bigint THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                ELSIF l_kind = 'uuid' THEN
                    l_start_uuid := i_start_values[l_position]::uuid;
                    l_end_uuid := i_end_values[l_position]::uuid;
                    IF l_first_difference = 0 AND l_start_uuid IS DISTINCT FROM l_end_uuid THEN
                        l_first_difference := CASE WHEN l_start_uuid < l_end_uuid THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                ELSE
                    -- text must be ordered under the column's own collation, which
                    -- is what the predicate uses; the session default can order the
                    -- same strings differently (for example 'a' < 'B' under
                    -- en-x-icu but not under C), which would wrongly reject a valid
                    -- chunk. Look the column's collation up from the catalog.
                    IF l_first_difference = 0
                        AND i_start_values[l_position] IS DISTINCT FROM i_end_values[l_position]
                    THEN
                        l_collation := NULL;
                        SELECT CASE
                                   WHEN a.attcollation = 0 THEN 'default'
                                   ELSE pg_catalog.quote_ident(c.collname)
                                   END
                        INTO l_collation
                        FROM pg_catalog.pg_attribute AS a
                                 LEFT JOIN pg_catalog.pg_collation AS c
                                           ON c.oid = a.attcollation
                        WHERE a.attrelid = pg_catalog.to_regclass(
                                pg_catalog.format('%I.%I', i_schema_name, i_table_name))
                          AND a.attname = i_primary_key_columns[l_position];

                        IF l_collation IS NULL THEN
                            -- The column is not in the catalog (a renderer unit
                            -- test with a synthetic table); compare as the default
                            -- collation, which is what the predicate would use.
                            l_start_less_than_end :=
                                    i_start_values[l_position] < i_end_values[l_position];
                        ELSE
                            EXECUTE pg_catalog.format(
                                    'SELECT %L::text COLLATE %s < %L::text COLLATE %s',
                                    i_start_values[l_position], l_collation,
                                    i_end_values[l_position], l_collation)
                                INTO l_start_less_than_end;
                        END IF;

                        l_first_difference := CASE WHEN l_start_less_than_end THEN -1 ELSE 1 END;
                        l_first_difference_position := l_position;
                    END IF;
                END IF;
            EXCEPTION
                WHEN invalid_parameter_value OR invalid_text_representation THEN
                    RAISE EXCEPTION
                        'boundary value for primary-key column % is not a valid %',
                        i_primary_key_columns[l_position], l_kind
                        USING ERRCODE = '22023';
            END;

            l_column_names := l_column_names || pg_catalog.format('%I.%I',
                                                                  i_table_alias,
                                                                  i_primary_key_columns[l_position]);
            l_start_literals := l_start_literals || pg_catalog.format('%L::%s',
                                                                      i_start_values[l_position],
                                                                      l_kind);
            l_end_literals := l_end_literals || pg_catalog.format('%L::%s',
                                                                  i_end_values[l_position],
                                                                  l_kind);
        END LOOP;

    -- The driving table is referenced as "<schema>.<table> <alias>" so the
    -- template's column references can use the alias.
    l_driving_table := pg_catalog.format('%I.%I %I',
                                         i_schema_name, i_table_name, i_table_alias);

    -- Reject a reversed chunk range (or an empty non-final one), which would
    -- render a predicate that matches no rows while the worker still marks the
    -- boundary complete. A final chunk may be a single row (start = end); any
    -- other chunk is half-open and needs start < end.
    IF l_first_difference > 0
        OR (NOT i_is_final AND l_first_difference = 0)
    THEN
        RAISE EXCEPTION 'chunk start boundary must be less than the end boundary'
            USING ERRCODE = '22023';
    END IF;

    -- Emit the chunk as a disjunction of axis-aligned boxes that exactly tiles the
    -- lexicographic range [start, end). Each box is a prefix of equalities plus a
    -- range on the next column, so PostgreSQL estimates it column by column and
    -- consumes it as a tight index range. A single row-value comparison instead
    -- multiplies the two bounds' selectivities independently, which overestimates
    -- a mid-table chunk enough to prefer a sequential scan of the whole table.
    --
    -- Let d be the first column whose bounds differ. The boxes are the lower tail
    -- (c_d = start_d and the suffix >= the lower suffix), the middle
    -- (start_d < c_d < end_d) and the upper tail (c_d = end_d and the suffix < the
    -- upper suffix, inclusive on the last column for the final chunk): 2*(n-d)+1
    -- boxes in all. A key that differs only in its last column is a single scalar
    -- range, and a key whose bounds are all equal is a single row.
    l_boxes := ARRAY []::text[];
    IF l_first_difference_position = 0 THEN
        -- All columns equal: exactly one row (a final chunk only; the check above
        -- rejects it otherwise).
        l_box := '';
        FOR l_position IN 1..l_column_count
            LOOP
                l_box := l_box || pg_catalog.format('%s%s = %s',
                                                    CASE WHEN l_position > 1 THEN ' AND ' ELSE '' END,
                                                    l_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_boxes := l_boxes || l_box;
    ELSIF l_first_difference_position = l_column_count THEN
        -- Only the last column differs: one box, a half-open (final: closed) scalar
        -- range after the fixed prefix.
        l_box := '';
        FOR l_position IN 1..l_column_count - 1
            LOOP
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    l_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_box := l_box || pg_catalog.format('%s >= %s AND %s %s %s',
                                            l_column_names[l_column_count],
                                            l_start_literals[l_column_count],
                                            l_column_names[l_column_count],
                                            CASE WHEN i_is_final THEN '<=' ELSE '<' END,
                                            l_end_literals[l_column_count]);
        l_boxes := l_boxes || l_box;
    ELSE
        -- Lower tail: c_d = start_d and the suffix >= the lower suffix. One box per
        -- suffix column: c_j > start_j, and >= start_n on the last column.
        FOR l_j IN l_first_difference_position + 1..l_column_count
            LOOP
                l_box := '';
                FOR l_position IN 1..l_first_difference_position
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            l_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                FOR l_position IN l_first_difference_position + 1..l_j - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            l_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                IF l_j < l_column_count THEN
                    l_box := l_box || pg_catalog.format('%s > %s',
                                                        l_column_names[l_j],
                                                        l_start_literals[l_j]);
                ELSE
                    l_box := l_box || pg_catalog.format('%s >= %s',
                                                        l_column_names[l_j],
                                                        l_start_literals[l_j]);
                END IF;
                l_boxes := l_boxes || l_box;
            END LOOP;

        -- Middle: start_d < c_d < end_d, after the fixed prefix.
        l_box := '';
        FOR l_position IN 1..l_first_difference_position - 1
            LOOP
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    l_column_names[l_position],
                                                    l_start_literals[l_position]);
            END LOOP;
        l_box := l_box || pg_catalog.format('%s > %s AND %s < %s',
                                            l_column_names[l_first_difference_position],
                                            l_start_literals[l_first_difference_position],
                                            l_column_names[l_first_difference_position],
                                            l_end_literals[l_first_difference_position]);
        l_boxes := l_boxes || l_box;

        -- Upper tail: c_d = end_d and the suffix < the upper suffix. One box per
        -- suffix column: c_j < end_j, and <= end_n on the last column for the
        -- final chunk (so the captured maximum row is processed).
        FOR l_j IN l_first_difference_position + 1..l_column_count
            LOOP
                l_box := '';
                FOR l_position IN 1..l_first_difference_position - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            l_column_names[l_position],
                                                            l_start_literals[l_position]);
                    END LOOP;
                l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                    l_column_names[l_first_difference_position],
                                                    l_end_literals[l_first_difference_position]);
                FOR l_position IN l_first_difference_position + 1..l_j - 1
                    LOOP
                        l_box := l_box || pg_catalog.format('%s = %s AND ',
                                                            l_column_names[l_position],
                                                            l_end_literals[l_position]);
                    END LOOP;
                IF l_j < l_column_count THEN
                    l_box := l_box || pg_catalog.format('%s < %s',
                                                        l_column_names[l_j],
                                                        l_end_literals[l_j]);
                ELSE
                    l_box := l_box || pg_catalog.format('%s %s %s',
                                                        l_column_names[l_j],
                                                        CASE WHEN i_is_final THEN '<=' ELSE '<' END,
                                                        l_end_literals[l_j]);
                END IF;
                l_boxes := l_boxes || l_box;
            END LOOP;
    END IF;

    -- Parenthesized so the disjunction drops into a template clause verbatim.
    l_chunking_clause := '(' || pg_catalog.array_to_string(l_boxes, ' OR ') || ')';

    -- A quoted identifier, or the caller's template, could in principle contain a
    -- sentinel character; reject that so the substitution below stays unambiguous.
    IF pg_catalog.strpos(l_driving_table, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_driving_table, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(l_chunking_clause, l_chunking_clause_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_driving_table_sentinel) > 0
        OR pg_catalog.strpos(i_sql_text, l_chunking_clause_sentinel) > 0
    THEN
        RAISE EXCEPTION 'template or identifier contains a reserved substitution character'
            USING ERRCODE = '22023';
    END IF;

    -- Replace each token with its sentinel first, then expand the sentinels.
    -- The substituted values are inserted last and are never re-scanned, so a
    -- value that literally contains the other token cannot mangle the output.
    l_rendered := pg_catalog.replace(i_sql_text, '<driving_table>', l_driving_table_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, '<chunking_clause>', l_chunking_clause_sentinel);
    l_rendered := pg_catalog.replace(l_rendered, l_driving_table_sentinel, l_driving_table);
    l_rendered := pg_catalog.replace(l_rendered, l_chunking_clause_sentinel, l_chunking_clause);

    RETURN l_rendered;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.render_chunk_sql IS
    'Returns the SQL template with <driving_table> and <chunking_clause> '
        'substituted for the given table, alias, primary-key columns and chunk '
        'range; each key kind (bigint, text or uuid) selects the explicit cast. '
        'The chunking clause is a disjunction of axis-aligned boxes that exactly '
        'tiles the lexicographic range, so the planner estimates it column by '
        'column and uses the primary-key index. The final chunk uses an inclusive '
        'upper bound, and a NULL boundary value is rejected. A text key is ordered '
        'under its column''s collation, matching the predicate.';
