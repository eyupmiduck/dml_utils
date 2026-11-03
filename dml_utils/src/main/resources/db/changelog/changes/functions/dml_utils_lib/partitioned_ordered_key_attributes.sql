CREATE OR REPLACE FUNCTION dml_utils_lib.partitioned_ordered_key_attributes(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text,
    i_index_name dml_utils_data.non_null_text
)
    RETURNS TABLE
            (
                ordinality  integer,
                column_name name,
                column_oid  oid,
                column_type pg_catalog.regtype,
                key_kind    text
            )
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
AS
$$
DECLARE
    l_index_oid oid;
    l_relkind   text;
    l_leaf      record;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
            i_schema_name => i_schema_name,
            i_table_name => i_table_name);

    SELECT i.indexrelid, c.relkind::text
    INTO l_index_oid, l_relkind
    FROM pg_catalog.pg_index AS i
             JOIN pg_catalog.pg_class AS ic ON ic.oid = i.indexrelid
             JOIN pg_catalog.pg_class AS c ON c.oid = i.indrelid
             JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
    WHERE n.nspname = i_schema_name
      AND c.relname = i_table_name
      AND ic.relname = i_index_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'index %.% does not exist on table %.%',
            i_schema_name, i_index_name, i_schema_name, i_table_name
            USING ERRCODE = '42704';
    END IF;

    IF l_relkind <> 'p' THEN
        RAISE EXCEPTION 'partition-wise ordered-key chunking requires a partitioned table, but %.% has relkind %',
            i_schema_name, i_table_name, l_relkind
            USING ERRCODE = '0A000';
    END IF;

    -- Walk the index inheritance tree once and pair each storage leaf with its
    -- descendant child index. The index tree mirrors the table tree, so a
    -- multi-level partition's leaf index is not a direct child of the parent.
    -- Checking the leaves before the parent keeps a missing child index (0A000)
    -- distinct from an invalid parent index (55000).
    FOR l_leaf IN
        WITH RECURSIVE index_tree AS (
            SELECT inh.inhrelid
            FROM pg_catalog.pg_inherits AS inh
            WHERE inh.inhparent = l_index_oid
            UNION ALL
            SELECT inh.inhrelid
            FROM pg_catalog.pg_inherits AS inh
                     JOIN index_tree AS tree ON inh.inhparent = tree.inhrelid
        ),
        child_indexes AS (
            SELECT ci.indrelid      AS leaf_oid,
                   tree.inhrelid    AS child_index_oid,
                   ic.relname::text AS child_index_name
            FROM index_tree AS tree
                     JOIN pg_catalog.pg_index AS ci ON ci.indexrelid = tree.inhrelid
                     JOIN pg_catalog.pg_class AS ic ON ic.oid = tree.inhrelid
        )
        SELECT live.leaf_ordinal,
               live.leaf_schema_name,
               live.leaf_name,
               child.child_index_oid,
               child.child_index_name
        FROM dml_utils_lib.leaf_partitions(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_chunk_by => 'ordered_key') AS live
                 LEFT JOIN child_indexes AS child ON child.leaf_oid = live.leaf_oid
        ORDER BY live.leaf_ordinal
        LOOP
            IF l_leaf.child_index_oid IS NULL THEN
                RAISE EXCEPTION 'leaf %.% has no matching child index for ordered key %.%',
                    l_leaf.leaf_schema_name, l_leaf.leaf_name, i_schema_name, i_index_name
                    USING ERRCODE = '0A000';
            END IF;

            -- Each child index must itself be usable (valid/ready/live and the same
            -- shape contract), so a broken or redefined leaf index fails before a
            -- run row exists.
            PERFORM 1
            FROM dml_utils_lib.ordered_key_attributes_for_index(
                    i_index_oid => l_leaf.child_index_oid,
                    i_schema_name => l_leaf.leaf_schema_name,
                    i_table_name => l_leaf.leaf_name,
                    i_index_name => l_leaf.child_index_name) AS a;
        END LOOP;

    -- The RETURN QUERY below validates the parent index shape after the leaf
    -- loop (an index built with ON ONLY is invalid and is rejected there).
    RETURN QUERY
        SELECT a.*
        FROM dml_utils_lib.ordered_key_attributes_for_index(
                i_index_oid => l_index_oid,
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_index_name => i_index_name) AS a;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.partitioned_ordered_key_attributes(
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text,
    dml_utils_data.non_null_text
    ) IS
    'Validates a partition-wise ordered key: the parent must be declarative '
        'partitioned, every storage leaf must have its matching descendant child '
        'index (validated in one index-tree walk), and the parent index must '
        'satisfy the ordered-key contract. Returns the parent index''s key columns '
        'and kinds. Rejects a missing index (42704), a non-partitioned parent '
        '(0A000), a leaf without a matching child index (0A000), and an unusable '
        'parent or child index (0A000/55000). VOLATILE because leaf_partitions '
        'calls the volatile pg_partition_tree.';
