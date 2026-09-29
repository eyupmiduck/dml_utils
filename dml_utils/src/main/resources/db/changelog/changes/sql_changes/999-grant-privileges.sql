-- Caller grants for the dml_utils objects. The changeset is runOnChange, so
-- keep the statements idempotent.
GRANT USAGE ON SCHEMA dml_utils TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_negative_integer TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_null_text TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_null_boolean TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_text_array TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_non_null_text_array TO dml_utils_caller;
GRANT USAGE ON DOMAIN dml_utils.non_empty_non_null_boolean_array TO dml_utils_caller;

REVOKE ALL ON dml_utils.example FROM public;
GRANT SELECT, INSERT, UPDATE, DELETE ON dml_utils.example TO dml_utils_caller;
