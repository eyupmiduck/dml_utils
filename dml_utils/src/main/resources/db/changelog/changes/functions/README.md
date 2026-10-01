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

### `dml_utils.reject_migration_run_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_run` that rejects any change to `label` or `chunk_size`, both fixed
when the run is created.

### `dml_utils.reject_migration_boundary_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_boundary` that rejects any change to `boundary_no` or `boundary_id`,
both fixed when the boundaries are computed.

### `dml_utils.populate_migration_boundaries(i_schema_name, i_table_name, i_label, i_sql_text, i_chunk_size)`

```sql
i_schema_name dml_utils.non_null_text
i_table_name  dml_utils.non_null_text
i_label       dml_utils.non_null_text
i_sql_text    dml_utils.non_null_text
i_chunk_size  dml_utils.positive_integer
RETURNS bigint
```

`SECURITY INVOKER`. Creates a `dml_utils.migration_run` row for the label, with
the recorded SQL text and chunk size, then inserts one fixed-row chunk boundary
per chunk plus a terminal high-water boundary at the captured maximum primary
key; returns the new `run_id`. The source table must exist and have a single
`bigint` primary key; the primary key is identified from the catalog, not
assumed to be `id`. Raises `23505` when an active (not archived) run already
exists for the label. Boundaries are inserted with `completed_at` null. An empty
source produces a run with no boundaries. Later inserts above the captured
maximum fall outside the terminal boundary and are not processed.

###

`dml_utils.run_migration_chunks(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size [, i_driving_table_alias])`

```sql
i_sql_text                  dml_utils.non_null_text
i_driving_table_schema_name dml_utils.non_null_text
i_driving_table_name        dml_utils.non_null_text
i_label                     dml_utils.non_null_text
i_chunk_size                dml_utils.positive_integer
i_driving_table_alias       dml_utils.non_null_text DEFAULT 't'
RETURNS void
```

`VOLATILE`, `SECURITY INVOKER`. Processes every fixed-row chunk of the driving
table, one `pg_background` worker per chunk. `i_sql_text` is a template with
`<driving_table>` and `<chunking_clause>` (see `render_chunk_sql`). Reuses the
active run for the label, or creates it by running `populate_migration_boundaries`
in a worker so its boundaries commit before the chunks run. Each chunk worker
claims its boundary and commits autonomously, so a re-run resumes at the first
unclaimed boundary. Raises `unique_violation` (`23505`) when another active run
already exists for the label, and re-raises a chunk worker's failure with its
original SQLSTATE. `RAISE NOTICE` and returns when the run is already complete.
Run under `READ COMMITTED`; do not hold locks on the driving table across the
call. Bounded worker waits are not part of this first version.

### `dml_utils.process_migration_chunk(i_run_id, i_boundary_no, i_sql_text)`

```sql
i_run_id      bigint
i_boundary_no bigint
i_sql_text    dml_utils.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Claims one boundary by setting its `completed_at` (an atomic
`UPDATE ... RETURNING` that locks the row for the caller's transaction) and then
runs the fully-formed chunk SQL. Raises `no_data_found` (`P0002`) when the
boundary does not exist or is already completed. If the chunk SQL fails, the
transaction aborts and the claim rolls back, so the chunk is retried on the next
run. Intended to run inside a `pg_background` worker, one call per chunk.

### `dml_utils.archive_migration_run(i_label)`

```sql
i_label dml_utils.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Sets `archived_at` on the active (not archived) run for the
label, if any, so the label can be reused; returns the archived `run_id`, or
NULL when the label had no active run. Pair it with
`populate_migration_boundaries` to rerun a label: populate, archive, populate
again.

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

### `dml_utils_lib.assert_no_active_run_for_label(i_label)`

```sql
i_label dml_utils.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `unique_violation` (`23505`) when a not-archived
migration run already exists for the label.

### `dml_utils_lib.assert_chunking_template(i_sql_text)`

```sql
i_sql_text dml_utils.non_null_text
RETURNS void
```

`IMMUTABLE`, `SECURITY INVOKER`. Raises `invalid_parameter_value` (`22023`)
unless `i_sql_text` contains `<driving_table>` and `<chunking_clause>` exactly
once each.

###

`dml_utils_lib.render_chunk_sql(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_name, i_start_id, i_end_id, i_is_final)`

```sql
i_sql_text         dml_utils.non_null_text
i_schema_name      dml_utils.non_null_text
i_table_name       dml_utils.non_null_text
i_table_alias      dml_utils.non_null_text
i_primary_key_name name
i_start_id         bigint
i_end_id           bigint
i_is_final         boolean
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Validates the template (see
`assert_chunking_template`) and returns it with `<driving_table>` replaced by
`"<schema>"."<table>" "<alias>"` and `<chunking_clause>` replaced by the
parenthesized range predicate `(<alias>.<pk> >= <start> AND <alias>.<pk> <op>
<end>)`, where `<op>` is `<` normally and `<=` for the final chunk. Identifiers
are quoted with `%I` and values with `%L`, so neither substitution can
reintroduce a token.
