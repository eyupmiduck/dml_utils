# Chunked-migration thread-scaling benchmark

A self-contained, repeatable benchmark of `dml_utils.run_migration_chunks` against
a synthetic table, sweeping the number of `pg_background` worker threads and
recording how long the primary-key range calculation and the per-chunk workload
take.

Everything needed to reproduce a run lives in this directory:

```
benchmark/
  config.env              every parameter (edit this to change the sweep)
  setup.sh                create/refresh the dedicated benchmark database
  run.sh                  run the sweep, appending results/<BENCH_CSV>
  report.sh               format the results into results/<BENCH_REPORT>
  sql/create-source.sql   deterministic synthetic source table + data
  sql/create-target.sql   throwaway destination the workload inserts into
  results/                generated: benchmark.csv, report.md, hardware.txt, ...
```

## What it measures

For each thread value in `BENCH_THREADS_FROM .. BENCH_THREADS_TO`, `run.sh` does
some unrecorded warm-up runs and then `BENCH_RUNS` measured runs. Each run calls
`run_migration_chunks` once and reads the run's timestamps back:

| column          | meaning                                                                  |
|-----------------|--------------------------------------------------------------------------|
| `range_seconds` | `boundaries_calculated_at - started_at` — the range calculation          |
| `chunk_seconds` | `completed_at - boundaries_calculated_at` — the workload over all chunks |
| `total_seconds` | `completed_at - started_at`                                              |

`report.sh` takes the mean of each over the recorded runs and prints a speedup
relative to the lowest thread value.

## Prerequisites

- Docker.
- The custom PostgreSQL image, e.g. `dml-utils-postgres:17-alpine`. `setup.sh`
  builds it with `scripts/build-postgres-image.sh postgres:17-alpine` if it is
  missing.
- Network access on first setup: the Liquibase image downloads its JDBC driver (`lpm add postgresql`).

## Quick start

```sh
benchmark/setup.sh            # create the database and data (add --refresh to recreate)
benchmark/run.sh              # run the sweep; appends results/benchmark.csv
benchmark/report.sh           # write results/report.md and print where it is
```

Re-run `run.sh` as many times as you like: the CSV accumulates, so the mean
covers every recorded run. `run.sh --reset` starts a fresh CSV. Change any
parameter in `config.env` and re-run `setup.sh --refresh` when the change affects
the server or the dataset.

## The benchmark database

The benchmark uses its **own** PostgreSQL container, not the shared dev stack, so
the server settings a reproducible sweep needs can be set without touching the dev
database:

- **Image**: `dml-utils-postgres:17-alpine` (the custom image with the
  `dml_utils_owner` / `dml_utils_caller` roles, `plpgsql_check` and
  `pg_background`).
- **Container / volume / network / port**: `BENCH_CONTAINER`,
  `BENCH_VOLUME`, `BENCH_NETWORK`, `BENCH_HOST_PORT` in `config.env`.
- **`max_worker_processes`**: set to `BENCH_MAX_WORKER_PROCESSES` (default 30).
  `run_migration_chunks` refuses a thread count above `max_worker_processes`, so
  this must be at least `BENCH_THREADS_TO` plus headroom for autovacuum and other
  workers. Changing it needs `setup.sh --refresh`.
- **Schema**: the changelog is applied to the container's `dml_utils` database by
  the Liquibase image, so the routines under test are exactly the repository's.
- **Data**: `sql/create-source.sql` creates `benchmark.bench_source` with a
  `bigint` primary key and deterministic payloads (`md5(id)`), so refreshing the
  database reproduces the table exactly. `benchmark.bench_target` is the
  throwaway destination.

All database access goes through `docker exec`, so no host `psql` is required.

## Parameters

All parameters live in [`config.env`](config.env) and are documented there. The
ones you are most likely to change:

| parameter                                 | default                | purpose                                  |
|-------------------------------------------|------------------------|------------------------------------------|
| `BENCH_ROWS`                              | `1000000`              | synthetic source rows                    |
| `BENCH_CHUNK_SIZE`                        | `1000`                 | rows per chunk                           |
| `BENCH_THREADS_FROM` / `BENCH_THREADS_TO` | `1` / `20`             | thread sweep                             |
| `BENCH_RUNS`                              | `5`                    | measured runs per thread value           |
| `BENCH_WARMUP_RUNS`                       | `1`                    | unrecorded warm-up runs per thread value |
| `BENCH_SQL`                               | INSERT into the target | the workload run once per chunk          |
| `BENCH_MAX_WORKER_PROCESSES`              | `30`                   | must cover `BENCH_THREADS_TO`            |
| `BENCH_SHARED_BUFFERS`                    | `512MB`                | server setting for the run               |

`BENCH_SQL` must contain `<driving_table>` and `<chunking_clause>` exactly once;
the driving table is aliased `t`. The default keeps the source table untouched by
copying each chunk into `benchmark.bench_target`, which is truncated before every
run.

## Findings

`report.sh` writes `results/report.md`: the configuration, the captured hardware
and PostgreSQL settings, the mean-times table with speedups, and the measurement
definitions and caveats. That page is the deliverable to share; regenerate it
after any additional runs. The raw per-run data is `results/benchmark.csv`, and
`results/hardware.txt` records the machine.

## Caveats

- The data is synthetic; absolute times are not production numbers. Use it to
  compare thread values on the same machine and dataset.
- The default workload is an `INSERT ... SELECT` from the source into a
  throwaway target, so the source is read-only and each run starts clean. Point
  `BENCH_SQL` at a different statement to benchmark something else, and note that
  `run_migration_chunks` assumes idempotent chunk SQL when resuming.
- Autovacuum, checkpoints and other background activity add noise. Increase
  `BENCH_RUNS` to tighten the means; warm-ups are excluded.
- Each thread is one `pg_background` worker, capped by `max_worker_processes`.
