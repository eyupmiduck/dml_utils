-- Rollback of the caller grants.
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils.example FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_empty_non_null_boolean_array FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_empty_non_null_text_array FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_empty_text_array FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_null_boolean FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_null_text FROM dml_utils_caller;
REVOKE USAGE ON DOMAIN dml_utils.non_negative_integer FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils FROM dml_utils_caller;
