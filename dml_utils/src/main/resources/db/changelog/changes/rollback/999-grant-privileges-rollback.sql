-- Rollback of the caller grants.
REVOKE SELECT, INSERT, UPDATE, DELETE ON dml_utils.example FROM dml_utils_caller;
REVOKE USAGE ON SCHEMA dml_utils FROM dml_utils_caller;
