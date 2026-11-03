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
    l_index_oid        oid;
    l_relkind          text;
    l_leaf             record;
    l_child_index      oid;
    l_child_index_name text;
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

    -- Validate the parent index shape; an index built with ON ONLY is invalid,
    -- so this also rejects a parent index with no usable child indexes.
    PERFORM 1
    FROM dml_utils_lib.ordered_key_attributes_for_index(
            i_index_oid => l_index_oid,
            i_schema_name => i_schema_name,
            i_table_name => i_table_name,
            i_index_name => i_index_name) AS a;

    -- Every storage leaf must have the matching descendant index. The index
    -- inheritance tree mirrors the table tree, so walk it rather than assuming
    -- the leaf index is a direct child of the parent.
    FOR l_leaf IN
        SELECT leaf.*
        FROM dml_utils_lib.leaf_partitions(
                i_schema_name => i_schema_name,
                i_table_name => i_table_name,
                i_chunk_by => 'ordered_key') AS leaf
        LOOP
            WITH RECURSIVE index_tree AS (
                SELECT inh.inhrelid
                FROM pg_catalog.pg_inherits AS inh
                WHERE inh.inhparent = l_index_oid
                UNION ALL
                SELECT inh.inhrelid
                FROM pg_catalog.pg_inherits AS inh
                         JOIN index_tree AS tree ON inh.inhparent = tree.inhrelid
            )
            SELECT tree.inhrelid, ic.relname::text
            INTO l_child_index, l_child_index_name
            FROM index_tree AS tree
                     JOIN pg_catalog.pg_index AS ci ON ci.indexrelid = tree.inhrelid
                     JOIN pg_catalog.pg_class AS ic ON ic.oid = tree.inhrelid
            WHERE ci.indrelid = l_leaf.leaf_oid;

            IF NOT FOUND THEN
                RAISE EXCEPTION 'leaf %.% has no matching child index for ordered key %.%',
                    l_leaf.leaf_schema_name, l_leaf.leaf_name, i_schema_name, i_index_name
                    USING ERRCODE = '0A000';
            END IF;

            -- The child index must itself be usable (valid/ready/live and the same
            -- shape contract), so a broken or redefined leaf index fails before a
            -- run row exists.
            PERFORM 1
            FROM dml_utils_lib.ordered_key_attributes_for_index(
                    i_index_oid => l_child_index,
                    i_schema_name => l_leaf.leaf_schema_name,
                    i_table_name => l_leaf.leaf_name,
                    i_index_name => l_child_index_name) AS a;
        END LOOP;

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
        'partitioned, the parent index must satisfy the ordered-key contract, and '
        'every storage leaf must have the matching descendant child index, itself '
        'usable. Returns the parent index''s key columns and kinds. Rejects a missing '
        'index (42704), a non-partitioned parent (0A000), an unusable parent or child '
        'index (0A000/55000) and a leaf without a matching child index (0A000).';
