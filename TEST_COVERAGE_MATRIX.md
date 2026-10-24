# dml_utils test-coverage matrix

Date: 2026-10-24

## Bottom line

- 47 SQL routines, 45 test classes, 371 tests.
- Every routine has at least one direct or indirect test path. There is no
  completely unreferenced routine.
- **Branch coverage is not measured.** The matrix records which tests exercise
  each routine and highlights branches that are visibly missing. It does not
  prove that all branches execute.
- `plpgsql_check` is static analysis, not coverage. The project has no JaCoCo,
  no PL/pgSQL branch-coverage instrumentation, and no coverage threshold.
- The previously confirmed gaps were closed by the new tests listed below; the
  remaining gap is limited to intentionally defensive exception handling.

## How to read the matrix

`Branches*` is a rough control-flow token count: `IF`, `ELSIF`, `CASE`, `WHEN`,
`EXCEPTION`, and `LOOP` in the routine source. It is useful for prioritising
review, not as a coverage percentage.

"Indirect" means the routine is exercised through another routine or through a
trigger; the test does not call it by name. Test-class names are from
`dml_utils/src/test/java/io/github/eyupmiduck/dmlutils`.

## Routine matrix

| Schema | Routine | Branches* | Test evidence |
|---|---|---:|---|
| `dml_utils` | `archive_migration_run` | 2 | MigrationMaintenanceTest |
| `dml_utils` | `cancel_migration_run` | 5 | CancelMigrationRunTest |
| `dml_utils` | `delete_archived_migration_runs` | 0 | MigrationMaintenanceTest (overload 1) |
| `dml_utils` | `delete_archived_migration_runs` | 0 | MigrationMaintenanceTest (overload 2) |
| `dml_utils` | `explain_function_over_table` | 2 | ExplainFunctionOverTableTest |
| `dml_utils` | `explain_function_over_table_chunk` | 0 | ExplainFunctionOverTableTest |
| `dml_utils` | `explain_migration_chunk` | 0 | ExplainMigrationChunksTest |
| `dml_utils` | `explain_migration_chunks` | 2 | ExplainMigrationChunksTest |
| `dml_utils` | `migration_boundaries` | 0 | MigrationMaintenanceTest |
| `dml_utils` | `migration_errors` | 0 | MigrationMaintenanceTest |
| `dml_utils` | `migration_run_summary` | 0 | MigrationMaintenanceTest |
| `dml_utils` | `run_function_over_table` | 7 | RunFunctionOverTableTest; LiquibaseUpgradeTest |
| `dml_utils` | `run_migration_chunks` | 71 | RunMigrationChunksTest; RunMigrationChunksBlockTest; RunMigrationChunksCancellationTest; RunMigrationChunksEdgeCasesTest; RunFunctionOverTableTest (indirect); LiquibaseUpgradeTest |
| `dml_utils` | `set_migration_run_function` | 3 | RunFunctionOverTableTest |
| `dml_utils` | `set_migration_run_sleep_ms` | 3 | RunMigrationChunksTest; MigrationTablesTest |
| `dml_utils` | `set_migration_run_sql_text` | 3 | RunMigrationChunksTest |
| `dml_utils` | `set_migration_run_threads` | 3 | RunMigrationChunksTest |
| `dml_utils_data` | `reject_migration_boundary_update` | 7 | MigrationTablesTest; MigrationBoundaryValidationTest (indirect trigger) |
| `dml_utils_data` | `reject_migration_run_update` | 10 | MigrationTablesTest (indirect trigger) |
| `dml_utils_data` | `set_updated_at` | 0 | MigrationTablesTest |
| `dml_utils_lib` | `assert_chunking_template` | 6 | RenderChunkSqlTest |
| `dml_utils_lib` | `assert_no_active_run_for_label` | 4 | AssertNoActiveRunForLabelTest |
| `dml_utils_lib` | `assert_plain_heap` | 8 | AssertPlainHeapTest |
| `dml_utils_lib` | `assert_relation_filepath` | 7 | RelationFilepathTest |
| `dml_utils_lib` | `assert_schema_exists` | 4 | AssertSchemaExistsTest |
| `dml_utils_lib` | `assert_table_exists` | 4 | AssertTableExistsTest |
| `dml_utils_lib` | `build_block_boundary_population_sql` | 3 | BuildBlockBoundaryPopulationSqlTest; RunMigrationChunksBlockTest (indirect) |
| `dml_utils_lib` | `build_boundary_population_sql` | 31 | BuildBoundaryPopulationSqlTest; PopulateMigrationBoundariesTest (indirect) |
| `dml_utils_lib` | `build_function_chunk_template` | 16 | RunFunctionOverTableTest (direct + validation) |
| `dml_utils_lib` | `explain_chunk_plan` | 3 | ExplainChunkPlansTest |
| `dml_utils_lib` | `explain_chunk_plans` | 6 | ExplainChunkPlansTest |
| `dml_utils_lib` | `explain_query_plan` | 3 | ExplainQueryPlanTest |
| `dml_utils_lib` | `migration_key_pair_values` | 14 | MigrationKeyPairValuesTest |
| `dml_utils_lib` | `migration_key_values` | 13 | MigrationBoundaryValidationTest |
| `dml_utils_lib` | `populate_migration_boundaries` | 9 | PopulateMigrationBoundariesTest; MigrationBoundaryValidationTest; RunMigrationChunksBlockTest (indirect) |
| `dml_utils_lib` | `prepare_chunk_sql_render` | 20 | PrepareChunkSqlRenderTest; RenderChunkSqlTest |
| `dml_utils_lib` | `primary_key_attributes` | 10 | PrimaryKeyAttributesTest |
| `dml_utils_lib` | `primary_key_columns` | 10 | MigrationBoundaryValidationTest |
| `dml_utils_lib` | `primary_key_kinds` | 5 | MigrationBoundaryValidationTest |
| `dml_utils_lib` | `process_migration_chunk` | 5 | ProcessMigrationChunkTest; RunMigrationChunksTest |
| `dml_utils_lib` | `record_migration_error` | 0 | MigrationErrorTest |
| `dml_utils_lib` | `relation_filepath` | 6 | RelationFilepathTest |
| `dml_utils_lib` | `render_block_chunk_sql` | 12 | RenderBlockChunkSqlTest |
| `dml_utils_lib` | `render_chunk_sql` | 0 | RenderChunkSqlTest |
| `dml_utils_lib` | `render_prepared_chunk_sql` | 80 | RenderPreparedChunkSqlTest; RenderChunkSqlTest |
| `dml_utils_lib` | `resolve_migration_run` | 34 | ResolveMigrationRunTest; RunMigrationChunksTest; RunMigrationChunksBlockTest; LiquibaseUpgradeTest |
| `dml_utils_lib` | `synthetic_chunk_boundary_values` | 13 | SyntheticChunkBoundaryValuesTest |

## Gaps closed by the new tests

1. **`run_migration_chunks`: benign `P0002` result.**
   `RunMigrationChunksEdgeCasesTest.treatsABoundaryClaimedByAnotherCoordinatorAsBenign`
   locks a boundary in another session before starting the coordinator. The
   launched worker blocks, then sees the completed boundary after the lock is
   released and returns `P0002`; the test proves the coordinator continues,
   records no error, and completes the run.

2. **`run_migration_chunks`: best-effort error-recording failure.**
   `RunMigrationChunksEdgeCasesTest.failureRecordingFailureDoesNotReplaceTheChunkError`
   installs a temporary trigger that makes every `migration_error` insert fail.
   The test proves the nested handler swallows the recording failure, leaves no
   partial error row, and re-raises the original chunk SQLSTATE (`22012`).

3. **`run_migration_chunks`: unexpected coordinator-side render failure drain.**
   `RunMigrationChunksEdgeCasesTest.unexpectedRenderFailureDrainsTheInFlightWorker`
   builds a run whose second boundary is reversed. Rendering that boundary raises
   `22023` while the first boundary's worker is still running; the test proves
   the worker finishes and commits before the original error is re-raised.

4. **`resolve_migration_run` and `populate_migration_boundaries`: NULL strategy.**
   `ResolveMigrationRunTest.rejectsANullChunkingStrategy` and
   `PopulateMigrationBoundariesTest.rejectsANullChunkingStrategy` assert
   SQLSTATE `22023`.

5. **`build_boundary_population_sql`: validation and key-kind branches.**
   `BuildBoundaryPopulationSqlTest` now directly covers empty arrays, a non-1
   lower bound, multidimensional arrays, NULL/empty column names, a NULL kind, a
   four-column key, a text key, a uuid key, duplicate-kind composite keys, and a
   mixed bigint/text composite key.

6. **`resolve_migration_run`: differing strategy and worker failure.**
   `ResolveMigrationRunTest.reusesTheStoredChunkingStrategy` exercises the
   differing-`chunk_by` branch and proves the stored strategy wins (the notice
   text itself is not captured). `propagatesANewRunBoundaryFailure` exercises the
   `pg_background_result` error path with a missing table and proves no run row is
   left behind.

## Remaining gap

The only unexercised branch family is the defensive exception handling around
`pg_background_cancel` / `pg_background_wait` / `pg_background_detach` during
cancellation and draining. These handlers are deliberate best-effort safety
nets; exercising them would require replacing extension functions or injecting
faults at a lower level than the public API. The successful cancellation path
itself is covered by `RunMigrationChunksCancellationTest`.

## Unmeasured, high-risk branches

These routines have many branches and broad tests, but no instrumentation to
prove full branch execution:

- `render_prepared_chunk_sql` (80): key kinds, text collations, templates,
  sentinel/final handling. `RenderChunkSqlTest` is extensive, but it is not a
  branch-coverage report.
- `run_migration_chunks` (71): scheduling, cancellation, throttling, drain,
  and failure paths. The previously confirmed gap branches now have targeted
  tests, but the routine still has no branch-coverage measurement.
- `resolve_migration_run` (34): resumed-input notices, filepath fail-closed
  check, and worker creation.
- `build_boundary_population_sql` (31): key-kind and validation combinations.
- `prepare_chunk_sql_render` (20): template validation, quoting, collation
  alignment.
- `build_function_chunk_template` (16): function/template validation paths.
- `migration_key_pair_values` (14) and `migration_key_values` (13): array shape,
  NULL positions, and mixed-kind packing.
- `reject_migration_run_update` (10) and `reject_migration_boundary_update` (7):
  the trigger tests reject updates, but the individual OR-chain sub-branches are
  not separately measured.
- `synthetic_chunk_boundary_values` (13), `render_block_chunk_sql` (12),
  `primary_key_attributes` (10), and `primary_key_columns` (10): covered by
  focused tests, but not branch-instrumented.

## Suggested next tests

1. **Branch tracing for the render/scheduling routines.** A test-only audit
   table or a PL/pgSQL coverage tool would turn the "unmeasured" rows into
   measured ones; `plpgsql_check` cannot do this.
2. **Low-level fault injection** for the cancellation/drain
   cancel/wait/detach exception handlers, if those safety nets are considered
   worth measuring.
3. **Per-column trigger tests** if the individual OR-chain sub-branches in
   `reject_migration_run_update` and `reject_migration_boundary_update` need
   explicit evidence beyond the current immutable-column tests.
4. **Notice-text capture** for the differing `chunk_by` resume if the message
   itself is part of the contract; the current test proves the stored strategy
   wins but does not capture the notice.

## Caveat

This matrix is evidence of test presence, not proof of full coverage. Without
branch instrumentation, the exact set of unexecuted branches in
`render_prepared_chunk_sql`, `run_migration_chunks`, and the other
high-complexity routines remains unknown. The gaps listed above were identified
from the source and test names and are now covered by targeted tests, except for
the remaining defensive handler family.
