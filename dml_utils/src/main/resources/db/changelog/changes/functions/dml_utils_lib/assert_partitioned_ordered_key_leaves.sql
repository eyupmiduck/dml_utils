CREATE OR REPLACE FUNCTION dml_utils_lib.assert_partitioned_ordered_key_leaves(
    i_run_id bigint,
    i_index_name dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_schema_name  text;
    l_table_name   text;
    l_parent_index oid;
    l_leaf         record;
BEGIN
    SELECT run.driving_table_schema_name::text, run.driving_table_name::text
    INTO l_schema_name, l_table_name
    FROM dml_utils_data.migration_run AS run
    WHERE run.run_id = i_run_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'migration run % does not exist', i_run_id
            USING ERRCODE = 'P0002';
    END IF;

    l_parent_index := pg_catalog.to_regclass(
            pg_catalog.format('%I.%I', l_schema_name, i_index_name));
    IF l_parent_index IS NULL THEN
        RAISE EXCEPTION 'index %.% does not exist on table %.%',
            l_schema_name, i_index_name, l_schema_name, l_table_name
            USING ERRCODE = '42704';
    END IF;

    -- Walk the index inheritance tree once and pair it with the manifest rows,
    -- so the set validated is exactly the set stored for the run (a leaf attached
    -- after the manifest insert is not silently accepted).
    FOR l_leaf IN
        WITH RECURSIVE
            index_tree AS (SELECT inh.inhrelid
                           FROM pg_catalog.pg_inherits AS inh
                           WHERE inh.inhparent = l_parent_index
                           UNION ALL
                           SELECT inh.inhrelid
                           FROM pg_catalog.pg_inherits AS inh
                                    JOIN index_tree AS tree ON inh.inhparent = tree.inhrelid),
            child_indexes AS (SELECT ci.indrelid      AS leaf_oid,
                                     tree.inhrelid    AS child_index_oid,
                                     ic.relname::text AS child_index_name
                              FROM index_tree AS tree
                                       JOIN pg_catalog.pg_index AS ci ON ci.indexrelid = tree.inhrelid
                                       JOIN pg_catalog.pg_class AS ic ON ic.oid = tree.inhrelid)
        SELECT leaf.leaf_ordinal,
               leaf.leaf_schema_name,
               leaf.leaf_name,
               child.child_index_oid,
               child.child_index_name
        FROM dml_utils_data.migration_run_leaf AS leaf
                 LEFT JOIN child_indexes AS child ON child.leaf_oid = leaf.leaf_oid
        WHERE leaf.run_id = i_run_id
        ORDER BY leaf.leaf_ordinal
        LOOP
            IF l_leaf.child_index_oid IS NULL THEN
                RAISE EXCEPTION 'leaf %.% has no matching child index for ordered key %.%',
                    l_leaf.leaf_schema_name, l_leaf.leaf_name, l_schema_name, i_index_name
                    USING ERRCODE = '0A000';
            END IF;

            PERFORM 1
            FROM dml_utils_lib.ordered_key_attributes_for_index(
                         i_index_oid => l_leaf.child_index_oid,
                         i_schema_name => l_leaf.leaf_schema_name,
                         i_table_name => l_leaf.leaf_name,
                         i_index_name => l_leaf.child_index_name) AS a;
        END LOOP;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.assert_partitioned_ordered_key_leaves(
    bigint,
    dml_utils_data.non_null_text
    ) IS
    'Validates the ordered-key child index of every leaf already snapshotted in a '
        'run''s manifest, walking multi-level index inheritance once. Raises 42704 '
        'for a missing parent index, 0A000 for a leaf without a matching child '
        'index, and 0A000/55000 for an unusable child index.';
