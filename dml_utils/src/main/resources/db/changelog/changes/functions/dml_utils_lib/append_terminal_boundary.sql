CREATE OR REPLACE FUNCTION dml_utils_lib.append_terminal_boundary(
    i_run_id bigint,
    i_leaf_ordinal integer,
    i_terminal_key dml_utils_data.migration_key
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS
$$
DECLARE
    l_run              record;
    l_leaf_schema_name text;
    l_leaf_name        text;
    l_boundary_no      bigint;
BEGIN
    -- A NULL run id or ordinal is a caller bug, not a missing object; name it as
    -- such. The terminal snapshot is a run state, so a NULL key stays 55000.
    IF i_run_id IS NULL THEN
        RAISE EXCEPTION 'i_run_id must not be NULL'
            USING ERRCODE = '22023';
    END IF;
    IF i_leaf_ordinal IS NULL OR i_leaf_ordinal < 1 THEN
        RAISE EXCEPTION 'leaf ordinal must be at least 1'
            USING ERRCODE = '22023';
    END IF;
    IF i_terminal_key IS NULL THEN
        RAISE EXCEPTION 'leaf % of run % has no terminal snapshot',
            i_leaf_ordinal, i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- Serialize on the run row. max(boundary_no)+1 is only safe while one
    -- appender holds the lock, and the same lock makes the single-terminal check
    -- and the milestone write atomic against finalization and leaf calculation.
    SELECT run.boundaries_calculated_at,
           run.cancelled_at
    INTO l_run
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id
        FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;
    IF l_run.cancelled_at IS NOT NULL THEN
        RAISE EXCEPTION 'run % is cancelled', i_run_id
            USING ERRCODE = '55000';
    END IF;
    IF l_run.boundaries_calculated_at IS NOT NULL THEN
        RAISE EXCEPTION 'run % already has its terminal boundary', i_run_id
            USING ERRCODE = '55000';
    END IF;

    -- The leaf identity comes from the manifest, never from the caller: the
    -- privilege check must authorize the leaf the row will actually be tagged
    -- with. A caller-supplied name only proved access to some other relation.
    SELECT identity.o_leaf_schema_name,
           identity.o_leaf_name
    INTO l_leaf_schema_name,
        l_leaf_name
    FROM dml_utils_lib.manifest_leaf_identity(
            i_run_id => i_run_id,
            i_leaf_ordinal => i_leaf_ordinal) AS identity;

    -- The terminal scan runs in the definer context, so the effective caller must
    -- still be allowed to read the leaf, as for leaf calculation.
    PERFORM dml_utils_lib.assert_caller_can_select_relation(
            i_schema_name => l_leaf_schema_name::dml_utils_data.non_null_text,
            i_table_name => l_leaf_name::dml_utils_data.non_null_text);

    -- Re-check the terminal leaf: the tree may have changed since its
    -- calculation (or since the last leaf committed).
    PERFORM dml_utils_lib.assert_migration_leaf_unchanged(
            i_run_id => i_run_id,
            i_leaf_ordinal => i_leaf_ordinal);

    SELECT coalesce(max(boundary.boundary_no), -1) + 1
    INTO l_boundary_no
    FROM dml_utils_data.migration_boundary AS boundary
    WHERE boundary.run_id = i_run_id;

    INSERT INTO dml_utils_data.migration_boundary
        (run_id, boundary_no, boundary_id, leaf_ordinal, completed_at)
    VALUES (i_run_id, l_boundary_no, i_terminal_key, i_leaf_ordinal, NULL);

    -- The terminal is not a chunk, so the chunk count is its boundary_no. Record
    -- the milestone here as well: this function is then the single-terminal gate
    -- on its own, and a repeat call is rejected above before it can append a
    -- second terminal. finalize_migration_boundaries keeps its own milestone
    -- write for the all-empty-run case (where there is no terminal boundary).
    UPDATE dml_utils_data.migration_run
    SET boundaries_calculated_at = pg_catalog.clock_timestamp(),
        total_chunks             = l_boundary_no
    WHERE run_id = i_run_id
      AND boundaries_calculated_at IS NULL;

    RETURN l_boundary_no;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.append_terminal_boundary(
    bigint,
    integer,
    dml_utils_data.migration_key
    ) IS
    'Appends the single terminal boundary for a partition-wise run after the '
        'last committed boundary and returns its boundary number (the run''s '
        'chunk count). Serializes on the run row, rejects a repeat append, a '
        'cancelled run, a NULL run id/ordinal (22023) and a missing snapshot '
        '(55000). The leaf identity and the caller''s SELECT privilege are taken '
        'from the manifest, not from the caller.';
