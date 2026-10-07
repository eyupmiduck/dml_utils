# Chunked-migration thread-scaling benchmark

A self-contained, repeatable benchmark of `dml_utils.run_migration_chunks` against
a synthetic table. It runs the migration with 1 thread, then 2, then 3, ... up to
a maximum you choose, several times at each value, and reports how long the
primary-key range calculation and the per-chunk workload took on average.

Everything needed to reproduce a run lives in this directory.

## How to run a benchmark, step by step

The worked example below runs **threads 1..20 with 10 measured runs each** on a **1,000,000-row** table. No prior
knowledge of the scripts is assumed.

### Step 0 — prerequisites

- Docker is installed and running (`docker info` works).
- The repository is checked out and its `scripts/` submodule is present (`git submodule update --init` if
  `scripts/start-local-db.sh` is missing).
- The first setup needs internet access: the Liquibase container downloads its
  PostgreSQL JDBC driver. `setup.sh` also builds the custom PostgreSQL image
  automatically if it is not present.

### Step 1 — set the parameters

Open [`config.env`](config.env) in an editor. For the example, make sure these
lines read:

```sh
BENCH_ROWS=1000000
BENCH_CHUNK_SIZE=1000
BENCH_THREADS_FROM=1
BENCH_THREADS_TO=20
BENCH_RUNS=10
BENCH_WARMUP_RUNS=1
```

These are all the defaults, so the example works out of the box; the point is
that you change the sweep here. The scripts read this file, so do not rely on
one-off environment overrides, or `report.sh` will describe the wrong settings.

### Step 2 — create the benchmark database (once)

From the repository root:

```sh
benchmark/setup.sh
```

This starts a dedicated PostgreSQL container, applies the `dml_utils` changelog
to it, and loads the deterministic 1,000,000-row source table. It is safe to
re-run. If you later change a **server setting** (for example
`BENCH_MAX_WORKER_PROCESSES`) or the **dataset**, recreate the container and
data with:

```sh
benchmark/setup.sh --refresh
```

### Step 3 — run the sweep

```sh
benchmark/run.sh --reset
```

- `--reset` starts a fresh results file. Omit it to add more runs to the
  existing file (the means then cover every recorded run).
- It prints one line per measured run as it finishes, and one per warm-up.
- This example runs `1..20` threads × (`1` warm-up + `10` measured) = **220
  runs**, each processing the whole table, so it can take a while (tens of
  minutes to a few hours depending on the machine). You can stop it with
  `Ctrl-C` at any time; the results already written are kept.
- To resume after a failure (or to add runs for only some thread values), set
  `BENCH_THREADS_FROM`/`BENCH_THREADS_TO` to the range you still need and run
  without `--reset`; the mean then covers every run recorded for that thread
  value. Use `--reset` instead to discard everything and start clean.

### Step 4 — format the results

```sh
benchmark/report.sh
```

This writes the findings page to `benchmark/results/report.md` and prints its
path. Re-run it any time to refresh the means after more runs.

### Step 5 — read the results

Open `benchmark/results/report.md`. It contains:

- the **configuration** that produced the results,
- the **hardware and PostgreSQL settings** captured on this machine,
- a **results table**, one row per thread value, with the mean range-calculation
  time, the mean chunk-run time, the mean total, and the speedup relative to the
  lowest thread value,
- a **chart** (`report.svg`, generated next to the page) of the mean total time
  against the thread count, embedded in the page.

The raw per-run data is `benchmark/results/benchmark.csv` (one row per measured
run); `benchmark/results/hardware.txt` and `benchmark/results/postgres-settings.txt`
hold the captured environment.

## What is measured

Each run calls `run_migration_chunks` once and reads the run's timestamps back:

| column in the CSV | meaning                                                                  |
|-------------------|--------------------------------------------------------------------------|
| `range_seconds`   | `boundaries_calculated_at - started_at` — the range calculation          |
| `chunk_seconds`   | `completed_at - boundaries_calculated_at` — the workload over all chunks |
| `total_seconds`   | `completed_at - started_at`                                              |

Warm-up runs are not recorded. `report.sh` averages each column over the recorded
runs for a thread value.

## Parameters

All parameters live in [`config.env`](config.env) and are documented there. The
ones you are most likely to change:

| parameter                                 | default                | purpose                                                 |
|-------------------------------------------|------------------------|---------------------------------------------------------|
| `BENCH_ROWS`                              | `1000000`              | synthetic source rows                                   |
| `BENCH_CHUNK_SIZE`                        | `1000`                 | rows per chunk                                          |
| `BENCH_THREADS_FROM` / `BENCH_THREADS_TO` | `1` / `20`             | thread sweep                                            |
| `BENCH_RUNS`                              | `10`                   | measured runs per thread value (the mean is over these) |
| `BENCH_WARMUP_RUNS`                       | `1`                    | unrecorded warm-up runs per thread value                |
| `BENCH_SQL`                               | INSERT into the target | the workload run once per chunk                         |
| `BENCH_MAX_WORKER_PROCESSES`              | `30`                   | must cover `BENCH_THREADS_TO`                           |
| `BENCH_SHARED_BUFFERS`                    | `512MB`                | server setting for the run                              |

`BENCH_SQL` must contain `<driving_table>` and `<chunking_clause>` exactly once;
the driving table is aliased `t`. The default keeps the source table untouched by
copying each chunk into `benchmark.bench_target`, which is truncated before every
run.

## The benchmark database

The benchmark uses its **own** PostgreSQL container, not the shared dev stack, so
the server settings a reproducible sweep needs can be set without touching the dev
database:

- **Image**: `dml-utils-postgres:17-alpine` (the custom image with the
  `dml_utils_owner` / `dml_utils_caller` roles, `plpgsql_check` and
  `pg_background`).
- **Container / volume / network / port**: `BENCH_CONTAINER`, `BENCH_VOLUME`,
  `BENCH_NETWORK`, `BENCH_HOST_PORT` in `config.env`.
- **`max_worker_processes`**: set to `BENCH_MAX_WORKER_PROCESSES` (default 30).
  `run_migration_chunks` refuses a thread count above `max_worker_processes`, so
  this must be at least `BENCH_THREADS_TO` plus headroom for autovacuum and other
  workers. Changing it needs `setup.sh --refresh`.
- **`pg_background.max_workers`**: pg_background independently caps concurrent
  workers per session (default 16). `run.sh` raises it to the run's thread count
  in the session that calls `run_migration_chunks`, so a sweep past 16 threads
  needs no server change.
- **Schema**: the changelog is applied to the container's `dml_utils` database by
  the Liquibase image, so the routines under test are exactly the repository's.
- **Data**: `sql/create-source.sql` creates `benchmark.bench_source` with a
  `bigint` primary key and deterministic payloads (`md5(id)`), so refreshing the
  database reproduces the table exactly. `benchmark.bench_target` is the
  throwaway destination.

All database access goes through `docker exec`, so no host `psql` is required.

## Files

```
benchmark/
  config.env              every parameter (edit this to change the sweep)
  setup.sh                create/refresh the dedicated benchmark database
  run.sh                  run the sweep, appending results/<BENCH_CSV>
  report.sh               format the results into results/<BENCH_REPORT>
  chart.awk               render the mean total time as the report's SVG chart
  sql/create-source.sql   deterministic synthetic source table + data
  sql/create-target.sql   throwaway destination the workload inserts into
  results/                generated: benchmark.csv, report.md, report.svg, hardware.txt, ...
```

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
