# Stored functions

One function per `.sql` file, grouped by the schema that owns it:

- `dml_utils/` — the caller-facing API (`run_migration_chunks`,
  `set_migration_run_sql_text`, `archive_migration_run`).
- `dml_utils_lib/` — the engine: the generic catalog and template helpers, and
  the internal routines that populate boundaries, run one chunk and record
  errors.
- `dml_utils_data/` — the data layer: the trigger functions that guard the
  migration tables.

Dependencies point downward: `dml_utils_lib` may reference `dml_utils_data`;
`dml_utils` may reference both; nothing references `dml_utils`.

`changes/functions.xml` loads them one `createProcedure` per `runOnChange`
changeset; the matching drop lives in `changes/functions-rollback/`. Every
routine is `SECURITY INVOKER` unless it genuinely needs `SECURITY DEFINER`.

The routines are intentionally **not an authorization boundary**: a routine runs
with the caller's privileges and can do nothing the caller could not do by
running its SQL directly, so no routine checks the caller's privileges. The
caller role is granted only the `EXECUTE`/`USAGE`/DML it needs (see
`sql_changes/999-grant-privileges.sql`), and `ALTER DEFAULT PRIVILEGES` keeps
future objects off `PUBLIC`. A helper that executes caller-supplied SQL (for
example `dml_utils_lib.process_migration_chunk`) is safe for the same reason — it
is no more privileged than the caller's own statements, not a sandbox.

One exception: `dml_utils_lib.migration_key_is_canonical` is loaded earlier, by
`changes/sql_changes/005-create-migration-key-check.sql`, because the
`migration_boundary_key_check` constraint is created with its table and calls it.
Its drop lives in `changes/rollback/`.

Each routine file ends with a `COMMENT ON FUNCTION` (or `COMMENT ON PROCEDURE`)
for the routine it creates, using the short form (`schema.name`, no argument
list). If a routine is ever overloaded, include its argument types so the
comment targets the right overload.

## `dml_utils`

###

`dml_utils.run_migration_chunks(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size [, i_driving_table_alias])`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_label                     dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_threads                   dml_utils_data.positive_integer DEFAULT 1
i_driving_table_alias       dml_utils_data.non_null_text DEFAULT 't'
RETURNS void
```

`VOLATILE`, `SECURITY INVOKER`. Processes every fixed-row chunk of the driving
table, up to `i_threads` `pg_background` workers at a time (default 1). Each
worker runs one chunk in its own transaction; the coordinator only schedules
and holds no locks on the driving table. On a failed chunk the coordinator
records it, stops launching new chunks, lets the workers already in flight
commit their current chunk, and then re-raises the error, leaving the run
incomplete so it can be resumed. `i_threads` must not exceed
`max_worker_processes` (`22023` otherwise), and parallel execution is not
always faster: the chunks never overlap, but they still contend for the same
table's locks and pages. `i_sql_text` is a template with
`<driving_table>` and `<chunking_clause>` (see `render_chunk_sql`). Reuses the
active run for the label, or creates it by running
`dml_utils_lib.populate_migration_boundaries` in a worker so its boundaries
commit before the chunks run (run resolution is delegated to
`dml_utils_lib.resolve_migration_run`). A resumed run uses the recorded SQL
text, chunk size, threads and driving table; a differing input is ignored with a
notice, so
the boundaries and the rendered chunk SQL always refer to the same table. Use
`set_migration_run_sql_text` or `set_migration_run_threads` to change the
recorded SQL text or thread count of an unfinished run. Each chunk
worker claims its boundary and commits autonomously, so a re-run resumes at the
first unclaimed boundary. Raises `unique_violation` (`23505`) when another
active run already exists for the label, and re-raises a chunk worker's failure
with its original SQLSTATE. `RAISE NOTICE` and returns when the run is already
complete. Run under `READ COMMITTED`; do not hold locks on the driving table
across the call. Bounded worker waits are not part of this first version.

### `dml_utils.set_migration_run_sql_text(i_label, i_sql_text)`

```sql
i_label    dml_utils_data.non_null_text
i_sql_text dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `sql_text` of the unfinished (`completed_at IS NULL`) run for the label, so
the next `run_migration_chunks`
call uses the adjusted SQL. Validates the new SQL as a chunking template (raising `invalid_parameter_value`, `22023`, if
it is not), and raises
`no_data_found` (`P0002`) when there is no unfinished run for the label. Use
this to adjust the SQL of an existing run (for example to fix a bad execution
plan) instead of passing a changed template to a resumed `run_migration_chunks`
call, which would ignore it.

###

`dml_utils.run_function_over_table(i_driving_table_schema_name, i_driving_table_name, i_function_schema_name, i_function_name [, i_chunk_size, i_threads, i_label])`

```sql
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_function_schema_name      dml_utils_data.non_null_text
i_function_name             dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer DEFAULT 1000
i_threads                   dml_utils_data.positive_integer DEFAULT 1
i_label                     text DEFAULT NULL
RETURNS void
```

`VOLATILE`, `SECURITY INVOKER`. A wrapper for `run_migration_chunks` that calls
a user-supplied function once per row of the driving table, chunk by chunk.
Instead of a hand-written template, the caller supplies a function whose
arguments are the driving table's primary-key columns, in key order, and which
returns `void`. The wrapper builds the per-row template
(`SELECT <fn>(t.<pk1>, ...) FROM <driving_table> WHERE <chunking_clause>`) and
delegates to `run_migration_chunks`, so it resumes by label exactly like a base
run. The function's argument types must equal the primary-key column types in key
order and it must return `void`; a missing function, a non-`void` return, or
mismatched argument types raise `invalid_parameter_value` (`22023`) before any
run is created. When `i_label` is NULL a deterministic label is derived from the
driving table and function, so re-running the same call resumes the same run.
Because a resumed run uses the recorded SQL text, use
`set_migration_run_function` to point an unfinished run at a different function.

### `dml_utils.set_migration_run_function(i_label, i_function_schema_name, i_function_name)`

```sql
i_label               dml_utils_data.non_null_text
i_function_schema_name dml_utils_data.non_null_text
i_function_name       dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `sql_text` of the unfinished run for
the label with the template that calls the given function over the run's driving
table (which is immutable), so the next `run_function_over_table` call uses the
adjusted function. Validates the function against the driving table's primary
key (raising `invalid_parameter_value`, `22023`, on a mismatch) and raises
`no_data_found` (`P0002`) when there is no unfinished run for the label.

### `dml_utils.set_migration_run_threads(i_label, i_threads)`

```sql
i_label   dml_utils_data.non_null_text
i_threads dml_utils_data.positive_integer
RETURNS void
```

`SECURITY INVOKER`. Replaces the recorded `threads` of the unfinished (`completed_at IS NULL`) run for the label, so the
next `run_migration_chunks`
call uses the adjusted worker count. Raises `no_data_found` (`P0002`) when there
is no unfinished run for the label. Use this to tune the parallelism of an
existing run instead of passing a changed `i_threads` to a resumed call, which
would ignore it.

### `dml_utils.archive_migration_run(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Sets `archived_at` on the active (not archived) run for the
label, if any, so the label can be reused; returns the archived `run_id`, or
NULL when the label had no active run. Pair it with
`dml_utils_lib.populate_migration_boundaries` to rerun a label: populate,
archive, populate again.

### `dml_utils.delete_archived_migration_runs()` and `dml_utils.delete_archived_migration_runs(i_label)`

```sql
-- delete_archived_migration_runs(): no arguments
-- delete_archived_migration_runs(i_label):
i_label dml_utils_data.non_null_text
RETURNS bigint
```

`SECURITY INVOKER`. Deletes archived runs — every one, or only those for the
label — and returns the number deleted. Deleting a run cascades to its
`migration_boundary` and `migration_error` rows. The overloads share a
changeset (`function-dml_utils.delete_archived_migration_runs`).

### `dml_utils.migration_run_summary(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS TABLE
( run_id, label, chunk_size, threads,
    driving_table_schema_name, driving_table_name,
    created_at, completed_at, archived_at,
    boundary_count, completed_boundary_count, error_count)
```

`STABLE`, `SECURITY INVOKER`. Returns one high-level row per run for the label,
including the number of boundaries (chunk starts plus the terminal boundary),
how many are completed, and the number of recorded errors. Use
`migration_errors` to list the errors themselves.

### `dml_utils.migration_errors(i_run_id)`

```sql
i_run_id bigint
RETURNS TABLE
    (error_id, boundary_no, sqlstate, message, created_at)
```

`STABLE`, `SECURITY INVOKER`. Returns the run's recorded errors, ordered by
`error_id`.

### `dml_utils.migration_boundaries(i_run_id)`

```sql
i_run_id bigint
RETURNS TABLE
    (boundary_no, boundary_id, completed_at)
```

`STABLE`, `SECURITY INVOKER`. Returns the run's chunk boundaries in order.
`boundary_id` is a `dml_utils_data.migration_key`: position-aligned arrays, one
per key kind. Index i is the i-th primary-key column, so a bigint key's first
column is `(boundary_id).bigint_values[1]`. A `completed_at` of `NULL` means the
chunk is still to process.

## `dml_utils_lib`

`dml_utils_lib` is the engine. It may reference `dml_utils_data`, but never
`dml_utils`.

### `dml_utils_lib.assert_schema_exists(i_schema_name)`

```sql
i_schema_name dml_utils_data.non_null_text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises `invalid_schema_name` (`3F000`) when the
schema does not exist.

### `dml_utils_lib.assert_table_exists(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS void
```

`STABLE`, `SECURITY INVOKER`. Raises `undefined_table` (`42P01`) when the table
does not exist in the schema.

### `dml_utils_lib.primary_key_attributes(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS TABLE (ordinality integer, column_name name, column_oid oid,
               column_type regtype, key_kind text)
```

`STABLE`, `SECURITY INVOKER`. The single catalog reader for a table's primary
key: `primary_key_columns` and `primary_key_kinds` delegate to it, so the key
order and the one-to-three-column validation live in one place. Each row is one
key column in key order, with its ordinal position, name, type oid, type and
collapsed boundary kind (`bigint`/`text`/`uuid`, or NULL for an unsupported
type). Raises `invalid_parameter_value` (`22023`) when the table has no primary
key or more than 3 key columns.

### `dml_utils_lib.primary_key_columns(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS name[]
```

`STABLE`, `SECURITY INVOKER`. Returns the table's primary-key columns in key
order (from the index, so a key declared `(b, a)` returns `{b, a}`), raising
`invalid_parameter_value` (`22023`) when the table has no primary key or more
than 3 key columns. Delegates the column read to `primary_key_attributes`.

### `dml_utils_lib.primary_key_kinds(i_schema_name, i_table_name)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
RETURNS text[]
```

`STABLE`, `SECURITY INVOKER`. Returns the boundary key kind of each primary-key
column, in key order: `bigint` for `smallint`/`integer`/`bigint`, `text` for a
`text` key, and `uuid` for a `uuid` key. Raises `invalid_parameter_value`
(`22023`) for any other type.

### `dml_utils_lib.migration_key_is_canonical(i_key)`

```sql
i_key dml_utils_data.migration_key
RETURNS boolean
```

`IMMUTABLE`, `SECURITY INVOKER`. True when the key is a canonical boundary key:
its arity is one to three, every present array shares that arity, exactly one
array holds a non-NULL element at each index, and no present array is empty or
all-NULL. The `migration_boundary_key_check` constraint calls it.

### `dml_utils_lib.migration_key_values(i_key, i_key_kinds)`

```sql
i_key       dml_utils_data.migration_key
i_key_kinds text[]
RETURNS text[]
```

`IMMUTABLE`, `SECURITY INVOKER`. Flattens a position-aligned `migration_key`
into one text value per primary-key column, in key order, using `i_key_kinds` to
pick the array for each position. The chunk predicate re-casts each value. Pure
casts and array element access, so it is `IMMUTABLE`.

###

`dml_utils_lib.build_function_chunk_template(i_table_schema_name, i_table_name, i_function_schema_name, i_function_name)`

```sql
i_table_schema_name    dml_utils_data.non_null_text
i_table_name           dml_utils_data.non_null_text
i_function_schema_name dml_utils_data.non_null_text
i_function_name        dml_utils_data.non_null_text
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Returns the `<driving_table>`/`<chunking_clause>`
template that calls the given function once per row, passing the driving table's
primary-key columns in key order (`SELECT <fn>(t.<pk1>, ...) FROM <driving_table> WHERE <chunking_clause>`).
Resolves the primary key via `primary_key_columns` and validates that the
function exists and returns `void` with argument types equal to the primary-key
column types in key order; raises `invalid_parameter_value` (`22023`) otherwise.

###
`dml_utils_lib.resolve_migration_run(i_sql_text, i_driving_table_schema_name, i_driving_table_name, i_label, i_chunk_size, i_threads, i_driving_table_alias)`

```sql
i_sql_text                  dml_utils_data.non_null_text
i_driving_table_schema_name dml_utils_data.non_null_text
i_driving_table_name        dml_utils_data.non_null_text
i_label                     dml_utils_data.non_null_text
i_chunk_size                dml_utils_data.positive_integer
i_threads                   dml_utils_data.positive_integer
i_driving_table_alias       dml_utils_data.non_null_text
RETURNS record (o_run_id bigint, o_already_completed boolean,
                o_effective_sql_text text, o_effective_schema_name text,
                o_effective_table_name text, o_effective_alias text,
                o_effective_threads integer, o_primary_key_columns name[],
                o_key_kinds text[])
```

`SECURITY INVOKER`. The run-resolution half of `run_migration_chunks`: it reuses
the active run for the label or creates one (populating its boundaries in a
`pg_background` worker so they commit autonomously), then returns the run id,
whether it was already complete, the effective sql_text/schema/table/alias/
threads (the stored values for a resumed run, ignoring differing inputs with a
notice) and the driving table's primary-key columns and kinds. It validates the
thread count against `max_worker_processes` before creating a run.

### `dml_utils_lib.assert_chunking_template(i_sql_text)`

```sql
i_sql_text dml_utils_data.non_null_text
RETURNS void
```

`IMMUTABLE`, `SECURITY INVOKER`. Raises `invalid_parameter_value` (`22023`)
unless `i_sql_text` contains `<driving_table>` and `<chunking_clause>` exactly
once each.

###

`dml_utils_lib.render_chunk_sql(i_sql_text, i_schema_name, i_table_name, i_table_alias, i_primary_key_columns, i_key_kinds, i_start_values, i_end_values, i_is_final)`

```sql
i_sql_text          dml_utils_data.non_null_text
i_schema_name       dml_utils_data.non_null_text
i_table_name        dml_utils_data.non_null_text
i_table_alias       dml_utils_data.non_null_text
i_primary_key_columns name[]
i_key_kinds         text[]
i_start_values      text[]
i_end_values        text[]
i_is_final          boolean
RETURNS text
```

`STABLE`, `SECURITY INVOKER`. Validates the template (see
`assert_chunking_template`) and returns it with `<driving_table>` replaced by
`"<schema>"."<table>" "<alias>"` and `<chunking_clause>` replaced by the
parenthesized row-value range predicate
`((<alias>.<pk1>, ...) >= ('<start1>'::<kind1>, ...) AND (<alias>.<pk1>, ...)
<op> ('<end1>'::<kind1>, ...))`, where `<op>` is `<` normally and `<=` for the
final chunk; a one-column key degenerates to an ordinary scalar comparison. Each
`i_key_kinds` entry must be `bigint`, `text` or `uuid` and is interpolated as the
literal's cast, so any other value raises `22023`; the arrays must all have the
same length, and a NULL start or end value raises `22023` (a NULL would render
as an unquoted `NULL`, making the predicate match no rows). The start and end
values are the text form of the packed boundary key. Identifiers are quoted with
`%I` and values with `%L`, so neither substitution can reintroduce a token.

### `dml_utils_lib.assert_no_active_run_for_label(i_label)`

```sql
i_label dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Raises `unique_violation` (`23505`) when a not-archived
migration run already exists for the label.

###

`dml_utils_lib.populate_migration_boundaries(i_schema_name, i_table_name, i_label, i_sql_text, i_chunk_size, i_threads)`

```sql
i_schema_name dml_utils_data.non_null_text
i_table_name  dml_utils_data.non_null_text
i_label       dml_utils_data.non_null_text
i_sql_text    dml_utils_data.non_null_text
i_chunk_size  dml_utils_data.positive_integer
i_threads     dml_utils_data.positive_integer DEFAULT 1
RETURNS bigint
```

`SECURITY INVOKER`. Creates a `dml_utils_data.migration_run` row for the label,
with the recorded SQL text, chunk size, threads and driving table, then inserts one
fixed-row chunk boundary per chunk plus a terminal high-water boundary at the
captured maximum primary key; returns the new `run_id`. The source table must
exist and have a primary key of one to three columns, each of a supported type (`smallint`, `integer`, `bigint`, `text`
or `uuid`); the columns are identified
from the catalog in key order, not assumed to be `id`. Each boundary is stored as
`dml_utils_data.migration_key`, which holds position-aligned arrays — index i is
the value of primary-key column i in the array matching that column's kind, and
exactly one array element is non-NULL per index. The `migration_boundary_key_check`
constraint enforces that: each present array must have a non-NULL element (so an
all-NULL or empty array is rejected), present arrays must share one length of one
to three, and exactly one of the three arrays holds a non-NULL value at each
index. Raises `23505` when
an active (not archived) run already exists for the label. Boundaries are
inserted with `completed_at` null. An empty source produces a run with no
boundaries. Later inserts above the captured maximum fall outside the terminal
boundary and are not processed.

### `dml_utils_lib.process_migration_chunk(i_run_id, i_boundary_no, i_sql_text)`

```sql
i_run_id      bigint
i_boundary_no bigint
i_sql_text    dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Claims one boundary by setting its `completed_at` (an atomic
`UPDATE ... RETURNING` that locks the row for the caller's transaction) and then
runs the fully-formed chunk SQL. Raises `no_data_found` (`P0002`) when the
boundary does not exist or is already completed. If the chunk SQL fails, the
transaction aborts and the claim rolls back, so the chunk is retried on the next
run. Intended to run inside a `pg_background` worker, one call per chunk.

### `dml_utils_lib.record_migration_error(i_run_id, i_boundary_no, i_sqlstate, i_message)`

```sql
i_run_id      bigint
i_boundary_no bigint
i_sqlstate    dml_utils_data.non_null_text
i_message     dml_utils_data.non_null_text
RETURNS void
```

`SECURITY INVOKER`. Inserts one row into `dml_utils_data.migration_error` for a
failed chunk worker. Intended to run inside a `pg_background` worker so the row
commits autonomously; `run_migration_chunks` calls it that way before re-raising
a chunk failure, so the error outlives the aborted caller transaction.

## `dml_utils_data`

### `dml_utils_data.set_updated_at()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`. The shared `BEFORE UPDATE ... FOR EACH ROW` trigger function
that stamps `NEW.updated_at := now()` on every table, so no caller can bypass
it. Attach it to each table with a trigger named `<table>_set_updated_at`; see
`changes/sql_changes/006-create-migration-tables.sql`.

### `dml_utils_data.reject_migration_run_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_run` that rejects any change to `label`, `chunk_size` or the driving
table (`driving_table_schema_name`, `driving_table_name`), all fixed when the run
is created.

### `dml_utils_data.reject_migration_boundary_update()`

```sql
RETURNS trigger
```

`SECURITY INVOKER`, not callable by users (PUBLIC `EXECUTE` is revoked and it is
granted to no one). The `BEFORE UPDATE ... FOR EACH ROW` trigger on
`migration_boundary` that rejects any change to `boundary_no` or `boundary_id`,
both fixed when the boundaries are computed.
