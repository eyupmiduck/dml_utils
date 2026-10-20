-- DROP FUNCTION removes the function's ACL. Re-assert it in the same changeset,
-- so a future edit to migration_run_summary.sql (even a comment-only one) cannot
-- leave dml_utils_caller without EXECUTE: 999-grant-privileges only re-runs when
-- its own SQL changes.
REVOKE EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) FROM public;
GRANT EXECUTE ON FUNCTION dml_utils.migration_run_summary(
    dml_utils_data.non_null_text
    ) TO dml_utils_caller;
