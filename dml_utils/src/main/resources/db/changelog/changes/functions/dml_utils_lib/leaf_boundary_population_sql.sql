CREATE OR REPLACE FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    i_chunk_by dml_utils_data.chunking_strategy,
    i_chunk_size integer,
    i_leaf_schema_name text,
    i_leaf_name text,
    i_run_id bigint,
    i_leaf_ordinal integer
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_chunk_by   dml_utils_data.chunking_strategy;
    l_descriptor record;
BEGIN
    -- The run's stored strategy is the single source of truth: the key descriptor
    -- below is resolved from it, so dispatching on the caller's argument could
    -- render a statement for a different strategy than the run chunks by.
    SELECT run.chunk_by
    INTO l_chunk_by
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    IF i_chunk_by IS NULL THEN
        RAISE EXCEPTION 'i_chunk_by must not be NULL'
            USING ERRCODE = '22023';
    END IF;

    IF i_chunk_by <> l_chunk_by THEN
        RAISE EXCEPTION 'run % uses chunk_by %, not %', i_run_id, l_chunk_by, i_chunk_by
            USING ERRCODE = '22023';
    END IF;

    IF l_chunk_by = 'blocks' THEN
        RETURN dml_utils_lib.build_block_leaf_boundary_population_sql(
                i_schema_name => i_leaf_schema_name,
                i_table_name => i_leaf_name,
                i_run_id => i_run_id,
                i_chunk_size => i_chunk_size::bigint,
                i_leaf_ordinal => i_leaf_ordinal);
    END IF;

    SELECT o_key_columns,
           o_key_kinds
    INTO l_descriptor
    FROM dml_utils_lib.migration_run_boundary_key(i_run_id => i_run_id);

    RETURN dml_utils_lib.build_leaf_boundary_population_sql(
            i_schema_name => i_leaf_schema_name,
            i_table_name => i_leaf_name,
            i_key_columns => l_descriptor.o_key_columns,
            i_key_kinds => l_descriptor.o_key_kinds,
            i_run_id => i_run_id,
            i_chunk_size => i_chunk_size::bigint,
            i_leaf_ordinal => i_leaf_ordinal);
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.leaf_boundary_population_sql(
    dml_utils_data.chunking_strategy,
    integer,
    text,
    text,
    bigint,
    integer
    ) IS
    'Returns the statement that appends one claimed leaf''s chunk starts, '
        'dispatching on the run''s stored chunk_by (the same strategy that drives '
        'the key descriptor). Raises invalid_parameter_value (22023) when '
        'i_chunk_by is NULL or differs from the stored strategy, and no_data_found '
        '(P0002) when the run does not exist. The caller executes the statement in '
        'the leaf calculation transaction.';
