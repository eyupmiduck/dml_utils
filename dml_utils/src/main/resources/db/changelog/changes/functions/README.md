# Stored functions

One function per `.sql` file, grouped by the schema that owns it:

- `dml_utils/` — the application surface, including the shared `set_updated_at()`
  trigger and the migration-boundary population routine.
- `dml_utils_lib/` — generic helpers that take their parameters explicitly,
  including the catalog validation routines.

`changes/functions.xml` loads them one `createProcedure` per `runOnChange`
changeset; the matching drop lives in `changes/functions-rollback/`. Every
routine is `SECURITY INVOKER` unless it genuinely needs `SECURITY DEFINER`.

Each routine file ends with a `COMMENT ON FUNCTION` (or `COMMENT ON PROCEDURE`)
for the routine it creates, using the short form (`schema.name`, no argument
list). If a routine is ever overloaded, include its argument types so the
comment targets the right overload.

## `dml_utils`

### `dml_utils.set_updated_at()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`. The shared `BEFORE UPDATE ... FOR EACH ROW` trigger function
that stamps `NEW.updated_at := now()` on every table, so no caller can bypass
it. Attach it to each table with a trigger named `<table>_set_updated_at`; see
`changes/sql_changes/004-create-migration-tables.sql`.

### `dml_utils.populate_migration_boundaries(i_schema_name, i_table_name, i_chunk_size)`

```sql
i_schema_name dml_utils.non_null_text
i_table_name  dml_utils.non_null_text
i_chunk_size  dml_utils.positive_integer
RETURNS bigint
```

`SECURITY INVOKER`. Creates a `dml_utils.migration_run` row and inserts one
fixed-row chunk boundary per chunk plus a terminal high-water boundary at the
captured maximum primary key; returns the new `run_id`. The source table must
exist and have a single `bigint` primary key; the primary key is identified from
the catalog, not assumed to be `id`. An empty source produces a run with no
boundaries. Later inserts above the captured maximum fall outside the terminal
boundary and are not processed.

## `dml_utils_lib`

### `dml_utils_lib.assert_schema_exists(i_schema_name)`

```sql
i_schema_name dml_utils.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `invalid_schema_name` (`3F000`) when the schema does
not exist.

### `dml_utils_lib.assert_table_exists(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils.non_null_text
i_table_name  dml_utils.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `undefined_table` (`42P01`) when the table does not
exist in the schema.

### `dml_utils_lib.single_column_primary_key(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils.non_null_text
i_table_name  dml_utils.non_null_text
RETURNS name
```

`SECURITY INVOKER`. Returns the table's single primary-key column, raising
`invalid_parameter_value` (`22023`) when the table has no primary key or a
composite primary key.

### `dml_utils_lib.assert_bigint_primary_key(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils.non_null_text
i_table_name  dml_utils.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `invalid_parameter_value` (`22023`) when the single
primary-key column is not `bigint`.
