#!/bin/sh
# Run the thread-scaling benchmark.
#
# For each thread value from BENCH_THREADS_FROM to BENCH_THREADS_TO it runs
# BENCH_WARMUP_RUNS unrecorded warm-ups, then BENCH_RUNS measured runs of
# dml_utils.run_migration_chunks, and appends one CSV row per measured run to
# results/<BENCH_CSV>. Results accumulate across invocations; pass --reset to
# start a fresh CSV. Format it with report.sh.
#
# Usage: benchmark/run.sh [--reset]

set -eu

here="$(cd "$(dirname "$0")" && pwd)"
. "$here/config.env"

# The chunk-size unit is determined by the strategy.
case "$BENCH_CHUNK_BY" in
    primary_key) chunk_unit="rows" ;;
    blocks) chunk_unit="blocks" ;;
    *) echo "BENCH_CHUNK_BY must be primary_key or blocks: $BENCH_CHUNK_BY" >&2; exit 2 ;;
esac

# The key column names (k1..kN), joined by '+' so a report group key has no
# spaces.
pk_columns_csv=""
_pk_i=0
while [ "$_pk_i" -lt "$BENCH_PK_COLUMNS" ]; do
    _pk_i=$((_pk_i + 1))
    pk_columns_csv="${pk_columns_csv:+$pk_columns_csv+}k$_pk_i"
done

usage() {
    echo "usage: $0 [--reset]" >&2
}

reset=0
for arg in "$@"; do
    case "$arg" in
        --reset) reset=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; usage; exit 2 ;;
    esac
done

command -v docker >/dev/null 2>&1 || {
    echo "docker is required but was not found on PATH" >&2
    exit 1
}

if ! docker inspect "$BENCH_CONTAINER" >/dev/null 2>&1 \
    || [ "$(docker inspect -f '{{.State.Running}}' "$BENCH_CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "benchmark container $BENCH_CONTAINER is not running; run benchmark/setup.sh first" >&2
    exit 1
fi

results_dir="$here/$BENCH_RESULTS_DIR"
csv="$results_dir/$BENCH_CSV"
mkdir -p "$results_dir"
if [ "$reset" = 1 ] || [ ! -f "$csv" ]; then
    printf 'strategy,chunk_unit,chunk_size,threads,run,chunks,range_seconds,chunk_seconds,total_seconds,pk_columns\n' > "$csv"
fi

psql_run() {
    docker exec "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}
# psql -c does not interpolate :variables, so the parameterised statements are
# fed on stdin with -f -.
psql_stdin() {
    docker exec -i "$BENCH_CONTAINER" psql -U "$BENCH_DB_USER" -d "$BENCH_DB" "$@"
}

# run_once <threads> <kind> <run_no> <record:0|1>
run_once() {
    threads="$1"
    kind="$2"
    run_no="$3"
    record="$4"

    label="bench-${threads}-${kind}-${run_no}-$(date +%s%N)"

    if [ "$BENCH_TRUNCATE_TARGET" = 1 ]; then
        psql_run -q -v ON_ERROR_STOP=1 \
            -c "TRUNCATE ${BENCH_SCHEMA}.${BENCH_TARGET_TABLE}"
    fi

    # pg_background caps concurrent workers per session at
    # pg_background.max_workers (16 by default), independently of the server's
    # max_worker_processes, so raise it to this run's thread count before
    # launching the workers. It is a user-settable GUC, so this needs no server
    # change.
    psql_stdin -q -v ON_ERROR_STOP=1 \
        -v sql="$BENCH_SQL" \
        -v schema="$BENCH_SCHEMA" \
        -v table="$BENCH_SOURCE_TABLE" \
        -v label="$label" \
        -v chunk="$BENCH_CHUNK_SIZE" \
        -v threads="$threads" \
        -v chunk_by="$BENCH_CHUNK_BY" \
        -f - >/dev/null <<'SQL'
SET pg_background.max_workers = :threads;
SELECT dml_utils.run_migration_chunks(
    i_sql_text => :'sql',
    i_driving_table_schema_name => :'schema',
    i_driving_table_name => :'table',
    i_label => :'label',
    i_chunk_size => :'chunk',
    i_threads => :'threads',
    i_chunk_by => :'chunk_by');
SQL

    # chunks, range_seconds, chunk_seconds, total_seconds. The chunk count is the
    # boundary count minus the terminal boundary.
    row="$(psql_stdin -At -F, -v ON_ERROR_STOP=1 -v label="$label" -f - <<'SQL'
SELECT (SELECT count(*) - 1
        FROM dml_utils_data.migration_boundary AS b
        JOIN dml_utils_data.migration_run AS r ON r.run_id = b.run_id
        WHERE r.label = :'label'),
       EXTRACT(EPOCH FROM (boundaries_calculated_at - started_at)),
       EXTRACT(EPOCH FROM (completed_at - boundaries_calculated_at)),
       EXTRACT(EPOCH FROM (completed_at - started_at))
FROM dml_utils_data.migration_run
WHERE label = :'label';
SQL
)"

    if [ "$record" = 1 ]; then
        printf '%s,%s,%s,%s,%s,%s,%s\n' "$BENCH_CHUNK_BY" "$chunk_unit" "$BENCH_CHUNK_SIZE" "$threads" "$run_no" "$row" "$pk_columns_csv" >> "$csv"
        echo "strategy=$BENCH_CHUNK_BY threads=$threads run=$run_no chunks=$(printf '%s' "$row" | cut -d, -f1) range=$(printf '%s' "$row" | cut -d, -f2)s chunk=$(printf '%s' "$row" | cut -d, -f3)s total=$(printf '%s' "$row" | cut -d, -f4)s" >&2
    else
        echo "strategy=$BENCH_CHUNK_BY threads=$threads warmup=$run_no done" >&2
    fi
}

echo "sweep: strategy=${BENCH_CHUNK_BY} (${chunk_unit}), threads ${BENCH_THREADS_FROM}..${BENCH_THREADS_TO}, ${BENCH_RUNS} measured + ${BENCH_WARMUP_RUNS} warm-up run(s) each" >&2
threads="$BENCH_THREADS_FROM"
while [ "$threads" -le "$BENCH_THREADS_TO" ]; do
    warmup=1
    while [ "$warmup" -le "$BENCH_WARMUP_RUNS" ]; do
        run_once "$threads" warmup "$warmup" 0
        warmup=$((warmup + 1))
    done
    run_no=1
    while [ "$run_no" -le "$BENCH_RUNS" ]; do
        run_once "$threads" run "$run_no" 1
        run_no=$((run_no + 1))
    done
    threads=$((threads + 1))
done

echo "results appended to $csv" >&2
