CREATE OR REPLACE FUNCTION dml_utils_lib.assert_migration_tree_unchanged(
    i_run_id bigint
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_partition_wise boolean;
    l_chunk_by       dml_utils_data.chunking_strategy;
    l_schema_name    text;
    l_table_name     text;
    l_manifest_count bigint;
    l_live_count     bigint;
    l_manifest       dml_utils_data.migration_run_leaf%ROWTYPE;
    l_live           record;
BEGIN
    SELECT run.partition_wise,
           run.chunk_by,
           run.driving_table_schema_name::text,
           run.driving_table_name::text
    INTO l_partition_wise, l_chunk_by, l_schema_name, l_table_name
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    -- A single-relation run has no manifest to compare.
    IF NOT l_partition_wise THEN
        RETURN;
    END IF;

    SELECT pg_catalog.count(*)::bigint
    INTO l_manifest_count
    FROM dml_utils_data.migration_run_leaf AS leaf
    WHERE leaf.run_id = i_run_id;

    -- Compare leaf by leaf in the deterministic manifest order, counting in the
    -- same pass. The live enumeration is the same function that built the
    -- manifest, so an unchanged tree matches position by position; any difference
    -- makes the stored ranges stale.
    l_live_count := 0;
    FOR l_live IN
        SELECT live.*
        FROM dml_utils_lib.leaf_partitions(
                     i_schema_name => l_schema_name,
                     i_table_name => l_table_name,
                     i_chunk_by => l_chunk_by) AS live
        ORDER BY live.leaf_ordinal
        LOOP
            l_live_count := l_live_count + 1;

            SELECT *
            INTO l_manifest
            FROM dml_utils_data.migration_run_leaf AS leaf
            WHERE leaf.run_id = i_run_id
              AND leaf.leaf_ordinal = l_live.leaf_ordinal;

            IF NOT FOUND THEN
                RAISE EXCEPTION 'partition tree changed for run %: leaf ordinal % is missing from the manifest',
                    i_run_id, l_live.leaf_ordinal
                    USING ERRCODE = '55000';
            END IF;

            IF l_manifest.leaf_schema_name::text IS DISTINCT FROM l_live.leaf_schema_name
                OR l_manifest.leaf_name::text IS DISTINCT FROM l_live.leaf_name
                OR l_manifest.leaf_oid IS DISTINCT FROM l_live.leaf_oid
                OR l_manifest.leaf_bound IS DISTINCT FROM l_live.leaf_bound
                OR (l_chunk_by = 'blocks'
                    AND l_manifest.relation_filepath IS DISTINCT FROM l_live.relation_filepath)
            THEN
                RAISE EXCEPTION 'partition tree changed for run %: leaf ordinal % no longer matches the manifest',
                    i_run_id, l_live.leaf_ordinal
                    USING ERRCODE = '55000';
            END IF;
        END LOOP;

    IF l_live_count IS DISTINCT FROM l_manifest_count THEN
        RAISE EXCEPTION 'partition tree changed for run %: % leaves now, % in the manifest',
            i_run_id, l_live_count, l_manifest_count
            USING ERRCODE = '55000';
    END IF;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_migration_tree_unchanged(bigint) IS
    'Re-enumerates a partition-wise run''s live leaf tree and compares it with the '
        'manifest (leaf count, order, schema/name/OID, partition bound and, for '
        'blocks, per-leaf filepath). Raises invalid_transaction_initiation (55000) on '
        'any difference so calculation and processing never mix stale and live '
        'leaves. A single-relation run is a no-op.';
