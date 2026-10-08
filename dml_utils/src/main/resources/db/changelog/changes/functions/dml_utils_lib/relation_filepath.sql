CREATE OR REPLACE FUNCTION dml_utils_lib.relation_filepath(
    i_schema_name dml_utils_data.non_null_text,
    i_table_name dml_utils_data.non_null_text
)
    RETURNS text
    LANGUAGE plpgsql
    STABLE
    SECURITY INVOKER
AS
$$
DECLARE
    l_relation regclass;
    l_filepath text;
BEGIN
    -- Resolve the relation with the schema qualified explicitly, so the result
    -- does not depend on the caller's search_path.
    l_relation := pg_catalog.to_regclass(pg_catalog.format('%I.%I', i_schema_name, i_table_name));

    IF l_relation IS NULL THEN
        RAISE EXCEPTION 'relation %.% does not exist', i_schema_name, i_table_name
            USING ERRCODE = '42P01';
    END IF;

    -- pg_relation_filepath returns the path relative to the data directory (for
    -- example base/16384/12345). It identifies the physical file, so it changes
    -- when the heap is rewritten (VACUUM FULL, CLUSTER, pg_repack, a tablespace
    -- move) and is the fingerprint a block run records and re-checks on resume.
    l_filepath := pg_catalog.pg_relation_filepath(l_relation);

    RETURN l_filepath;
END;
$$;

COMMENT ON FUNCTION dml_utils_lib.relation_filepath IS
    'Returns the pg_relation_filepath of the given table (its physical file, '
        'relative to the data directory), or raises undefined_table (42P01) when '
        'it does not exist. Used as the physical fingerprint of a block-chunked '
        'run: the filepath changes when the heap is rewritten.';
