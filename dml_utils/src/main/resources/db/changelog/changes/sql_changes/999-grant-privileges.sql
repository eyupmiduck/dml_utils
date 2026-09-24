-- Caller grants for the dml_utils objects. The changeset is runOnChange, so
-- keep the statements idempotent.
GRANT USAGE ON SCHEMA dml_utils TO dml_utils_caller;

REVOKE ALL ON dml_utils.example FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils.example TO dml_utils_caller;
