CREATE OR REPLACE FUNCTION dml_utils.populate_migration_boundaries(
    i_schema_name dml_utils.non_null_text,
    i_table_name  dml_utils.non_null_text,
    i_chunk_size  dml_utils.positive_integer
)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY INVOKER
AS
$$
DECLARE
    l_primary_key_name name;
    l_run_id           bigint;
BEGIN
    PERFORM dml_utils_lib.assert_schema_exists(i_schema_name => i_schema_name);
    PERFORM dml_utils_lib.assert_table_exists(
        i_schema_name => i_schema_name,
        i_table_name => i_table_name);
    l_primary_key_name := dml_utils_lib.single_column_primary_key(
        i_schema_name => i_schema_name,
        i_table_name => i_table_name);
    PERFORM dml_utils_lib.assert_bigint_primary_key(
        i_schema_name => i_schema_name,
        i_table_name => i_table_name);

    INSERT INTO dml_utils.migration_run DEFAULT VALUES
    RETURNING run_id
    INTO l_run_id;

    -- One starting boundary per chunk plus the final high-water boundary
    -- (boundary_no = max chunk_no + 1, boundary_id = max primary key). The
    -- identifiers are the only dynamic parts; the chunk size and run id are
    -- bound as parameters.
    EXECUTE pg_catalog.format(
        $chunk$
        INSERT INTO dml_utils.migration_boundary (run_id, boundary_no, boundary_id)
        WITH numbered AS MATERIALIZED (
            SELECT
                %1$I AS id,
                row_number() OVER (ORDER BY %1$I) AS rn
            FROM %2$I.%3$I
        ),
        chunked AS (
            SELECT
                id,
                ((rn - 1) / $2::bigint)::bigint AS chunk_no
            FROM numbered
        )
        SELECT
            $1,
            CASE
                WHEN GROUPING(chunk_no) = 1
                    THEN max(chunk_no) + 1
                ELSE chunk_no
            END AS boundary_no,
            CASE
                WHEN GROUPING(chunk_no) = 1
                    THEN max(id)
                ELSE min(id)
            END AS boundary_id
        FROM chunked
        GROUP BY GROUPING SETS (
            (chunk_no),
            ()
        )
        HAVING count(*) > 0
        $chunk$,
        l_primary_key_name,
        i_schema_name,
        i_table_name)
    USING l_run_id, i_chunk_size;

    RETURN l_run_id;
END;
$$;

COMMENT ON FUNCTION dml_utils.populate_migration_boundaries IS
    'Creates a migration run and populates its fixed-row chunk boundaries for '
        'the given table, returning the new run_id.';
