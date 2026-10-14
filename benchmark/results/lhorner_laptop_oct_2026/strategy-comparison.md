# Chunking strategy comparison: `primary_key` vs `blocks`

Compares the two chunking strategies across the two recorded runs:

- `1_col_pk` — 1,000,000 rows / 9,346 blocks, key `k1`
- `3_col_pk` — 10,000,000 rows / 113,637 blocks, key `k1+k2+k3`

Figures are the mean over the 10 measured runs per (strategy, thread) in each
`benchmark.csv`. The sibling `report.md` files only captured the threads=1 slice,
so they are not used here.

Caveat on scope: the two datasets differ 10x in row count and the block chunk
size differs slightly (10 vs 11 blocks), so **absolute** per-row costs are not a
clean 1-column-vs-3-column comparison. What is comparable is the **relative
behaviour of the two strategies within each dataset**.

## 1. Setup and boundary calculation

| dataset | strategy | chunks | rows/chunk | blocks/chunk | range calc | share of total |
|---|---|---:|---:|---:|---:|---:|
| 1_col (1M) | primary_key | 1000 | 1000 | 9.3 | 0.181 s | 1.4% |
| 1_col (1M) | blocks | 935 | 1070 | 10.0 | 0.033 s | 0.3% |
| 3_col (10M) | primary_key | 10000 | 1000 | 11.4 | 1.964 s | 1.1% |
| 3_col (10M) | blocks | 10331 | 968 | 11.0 | 0.283 s | 0.2% |

Both strategies are configured to produce about the same number of chunks, so
the parallelism granularity is comparable. `blocks` builds its boundaries ~5.5-7x
cheaper (`pg_relation_size` block count vs a key-index scan) and stays cheap as
the table grows. But boundary calculation is at most 1.4% of the total in every
case, so it does **not** decide the winner here; the chunk-run cost does. It
would matter more with a very cheap chunk SQL or a much larger chunk count.

## 2. Per-row chunk cost

At 1 thread (mean of 10 runs), normalised per row:

| dataset | primary_key | blocks | blocks vs primary_key |
|---|---:|---:|---:|
| 1_col | 13.0 us/row | 11.9 us/row | ~9% cheaper |
| 3_col | 18.1 us/row | 18.4 us/row | ~equal (1.5% dearer) |

This is the core difference. `blocks` chunks are physically sequential ctid
ranges, so the workload reads the heap front to back; `primary_key` walks the
primary-key index and then does random heap fetches. On the 1M-row set, which is
largely cache-resident, sequential access is a real ~9% win. On the 10M-row set
the run is dominated by the INSERT's WAL/IO, so scan order stops mattering and
the two converge.

## 3. Head-to-head ratio across threads

`blocks / primary_key` mean total time (<1 means `blocks` is faster):

| dataset | t1 | t2 | t4 | t8 | t12 | t16 | t20 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1_col | 0.90 | 0.90 | 0.93 | 0.90 | 0.85 | 0.87 | 0.89 |
| 3_col | 1.01 | 1.04 | 0.88 | 0.87 | 1.11 | 0.89 | 1.03 |

`blocks` is consistently 7-15% faster on the 1-col set at every thread level. On
the 3-col set it is a coin flip (wins at 4/8/16, loses at 1/2/12/20); that
variation is noise, not signal.

## 4. Scaling shape and stability

Speedup against each strategy's own 1-thread time:

| dataset | strategy | t4 | t8 | t12 | t16 | t20 |
|---|---|---:|---:|---:|---:|---:|
| 1_col | primary_key | 3.4x | 5.3x | 6.1x | 6.6x | 6.4x |
| 1_col | blocks | 3.3x | 5.3x | 6.5x | 6.9x | 6.4x |
| 3_col | primary_key | 2.8x | 3.4x | 3.9x | 3.3x | 3.2x |
| 3_col | blocks | 3.2x | 3.9x | 3.6x | 3.7x | 3.2x |

Run-to-run stability (coefficient of variation of `total_seconds` across the 10
runs):

| dataset | strategy | t1 | t4 | t8 | t16 | t20 |
|---|---|---:|---:|---:|---:|---:|
| 1_col | primary_key | 1.9% | 2.3% | 3.2% | 0.8% | 0.8% |
| 1_col | blocks | 2.6% | 4.4% | 5.5% | 0.7% | 2.6% |
| 3_col | primary_key | 2.4% | 7.5% | 7.9% | 11.7% | 10.6% |
| 3_col | blocks | 4.2% | 1.5% | 3.2% | 4.4% | 5.6% |

Two clear patterns on the large dataset:

- `primary_key` scales to about 12 threads and then regresses (3.9x -> 3.2x at
  20). Its concurrent workers do random index+heap reads that thrash the 512 MB
  `shared_buffers`, so past ~12 workers it is contention-bound.
- `blocks` peaks a little earlier (8) but degrades gracefully and stays flat to
  16, and its variance is 2-8x lower at high thread counts (CV 4-6% vs
  `primary_key`'s 8-12%). Sequential block reads are far less sensitive to buffer
  residency and inter-worker interference, so runtimes are predictable.

The 1-col set is CPU/cache-bound and both strategies behave almost identically
(peak ~7x at 16 threads on 20 logical CPUs), with low variance throughout.

## 5. Takeaway

- For small/medium, cache-resident tables (1M class), `blocks` is the modestly
  better strategy: ~10% faster end to end, mostly from sequential heap access,
  plus a cheaper boundary calculation.
- For large, I/O-bound tables (10M class), the two are equal on median
  throughput, but `blocks` is the more predictable, better-scaling choice:
  `primary_key` goes unstable and regresses past ~12 workers. At high thread
  counts on big tables, sequential block chunks buy stability rather than raw
  speed.
- The usual counterweights still apply: `blocks` requires a quiescent, read-only
  plain heap and is not resumable across a heap rewrite; `primary_key` is stable
  and resumable. The strategy decision is therefore driven by table size and how
  deterministic the runtimes must be, not by a flat speed ranking.
