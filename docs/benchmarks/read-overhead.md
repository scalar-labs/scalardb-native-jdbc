# Read overhead of the committed image (2026-10-11)

How much slower a read of ScalarDB's committed image is than the same query on the plain tables, per
engine, query shape and rewrite. Benchmark: `driver/src/integrationTest/.../bench/ReadOverheadBenchmark.java`,
run with `./gradlew :driver:integrationTest --tests '*ReadOverheadBenchmark*' -Dnativejdbc.bench=true`.

Setup: customers `c` (100k rows) and orders `o` (500k rows, five per customer), each with a metadata table
in ScalarDB's decoupled layout holding 100 rows in flight; secondary indexes `c(region)`,
`o(customer_id)`; the in-flight index of the setup. Statistics gathered, and on Oracle and MySQL a
histogram on `tx_state` (without it both engines take `tx_state IN (1, 2)` for most of the table and
scan it: Oracle 22x on every query reading `o`, MySQL 12-69x on anti-joins). Measured through the vendor
drivers directly, so only the SQL is compared: median of up to 200 executions of a prepared statement
with random parameters, after a warm-up; local containers on an Apple Silicon laptop, SQL Server under
x86 emulation. Every rewrite returned the rows of BASE.

Rewrites:

- BASE: the query on the data tables, ignoring the metadata; the target.
- VIEW_LJ, VIEW_AJ: through the UNION ALL view `rc_T` (milestone 3), its committed arm a left join on
  the metadata or an anti-join against the in-flight rows (`NOT EXISTS`).
- VIEW_CASE: through a single left-join view with a CASE per column (before todo 071).
- SPLIT_*: the query itself repeated per combination of committed and in-flight copies of its
  ScalarDB tables (2^n copies for n tables) and joined with UNION ALL; ORDER BY, LIMIT and
  aggregates on top; a ScalarDB table on the nullable side of a LEFT JOIN as an inner join per copy
  plus the unmatched rows; an IN subquery split at the top level. They differ in how a committed
  table excludes its rows in flight: SPLIT_LJ a left join on the metadata, SPLIT_AJ `NOT EXISTS`,
  SPLIT_NI `NOT IN`, SPLIT_HJ / SPLIT_HD a left join to the in-flight keys (HD with `SELECT
  DISTINCT`); SPLIT_PA / SPLIT_PH are AJ / HD with a whole-query aggregate computed per copy;
  SPLIT_MX / SPLIT_MH are AJ / HD in the all-committed copy and LJ in the others; SPLIT_LT / SPLIT_LH
  use n + 1 copies through LATERAL (rejected below).

## Conclusions

The read algorithm the measurements lead to (sections below, in the order they were measured):

1. **Split the query, never read through a view.** No engine pushes a join predicate into a UNION
   ALL view whose arms are joins, PostgreSQL included: through `rc_T` a key join from a plain table is
   24-236x and a join of two ScalarDB tables up to 1800x; the CASE view is 40-800x on any non-key
   predicate. The split is 2^n copies; n + 1 copies through LATERAL lose badly once rows are in
   flight (up to 2900x on MariaDB, 3-25x elsewhere).
2. **Form of the committed check, per engine:**

   | engine | all-committed copy | copies with a table in flight | whole-query aggregate |
   |---|---|---|---|
   | PostgreSQL | NOT EXISTS | NOT EXISTS | over the union |
   | MySQL, MariaDB | DISTINCT in-flight set | DISTINCT in-flight set | per copy |
   | SQL Server | NOT EXISTS (1-2 tables), DISTINCT set (3+) | left join on the metadata | per copy |
   | Oracle | NOT EXISTS | NOT EXISTS | per copy |

3. **Statistics.** Oracle and MySQL need a histogram on `tx_state`; without it both take
   `tx_state IN (1, 2)` for most of the table and scan it (Oracle 22x, MySQL 12-69x).
4. **IN subqueries** are split at the top level: an IN over a UNION runs once per outer row on
   MySQL and MariaDB.
5. **PostgreSQL parallel plans.** The split's estimated cost makes PostgreSQL start parallel workers
   (about 10 ms) on statements that take 1 ms: 12-28x. Keep `max_parallel_workers_per_gather = 0` on
   the session and enable parallel plans only around a statement whose plain query's plan is
   parallel (a long one); setting and resetting it around every statement costs about 1 ms each.
6. **Result.** Locally, with random keys: one-table shapes 1.0-1.7x, joins of two tables 0.4-2.6x,
   joins returning many rows 1.0-2.3x, three tables 0.4-3.1x, four tables 0.7-5.2x, full scans
   1.0-2.5x. With every key in flight (a worst case): mostly 1-3.5x, four tables up to 5-7x on SQL
   Server, MySQL and MariaDB. With a 1 ms round trip to the database, short queries come within
   1.0-1.8x, three tables 0.6-2.2x, four tables 0.8-3.4x; full scans and large results keep their
   local ratios. Below 1.0x is Oracle choosing a better plan for the split than for the plain query.
7. **Not measured:** the driver's own cost per statement, parsing and rewriting (about 0.3 ms
   without a cache, todo 008), and joins of five or more ScalarDB tables (32 copies and up).

## Ratios to BASE, indexed shapes (run with the statistics and IN fixes)

### POSTGRESQL

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ |
|---|---|---|---|---|---|---|
| key lookup | 0.58 (1.0x) [1.32] | 0.49 (0.8x) [1.00] | 0.44 (0.8x) [1.11] | 0.43 (0.7x) [0.90] | 0.43 (0.7x) [1.01] | 0.42 (0.7x) [0.94] |
| non-key filter (100 rows) | 0.51 (1.0x) [0.90] | 0.56 (1.1x) [0.64] | 0.48 (0.9x) [0.56] | 21.66 (42.3x) [22.98] | 0.62 (1.2x) [1.74] | 0.50 (1.0x) [0.68] |
| FK filter, ORDER BY, LIMIT 3 | 0.44 (1.0x) [1.31] | 0.50 (1.1x) [0.83] | 0.44 (1.0x) [0.95] | 285.65 (650.2x) [351.53] | 0.53 (1.2x) [1.09] | 0.48 (1.1x) [0.97] |
| COUNT by FK | 0.48 (1.0x) [1.33] | 0.48 (1.0x) [1.01] | 0.43 (0.9x) [0.89] | 79.58 (167.0x) [87.70] | 0.52 (1.1x) [0.93] | 0.49 (1.0x) [1.45] |
| plain table JOIN on key (10 rows) | 0.41 (1.0x) [0.56] | 24.35 (59.6x) [26.27] | 9.90 (24.3x) [10.68] | 0.44 (1.1x) [0.78] | 0.44 (1.1x) [0.73] | 0.47 (1.2x) [0.83] |
| key JOIN FK, both ScalarDB | 0.43 (1.0x) [0.78] | 0.48 (1.1x) [0.87] | 0.44 (1.0x) [0.62] | 97.40 (224.9x) [104.67] | 0.52 (1.2x) [1.07] | 0.47 (1.1x) [0.88] |
| plain table JOIN on non-key (1000 rows) | 3.34 (1.0x) [5.10] | 24.07 (7.2x) [25.36] | 10.87 (3.3x) [12.16] | 25.52 (7.7x) [27.25] | 4.59 (1.4x) [6.14] | 3.65 (1.1x) [4.33] |
| IN subquery on non-key | 1.13 (1.0x) [1.78] | 83.66 (74.2x) [90.55] | 45.32 (40.2x) [48.88] | 410.62 (364.0x) [428.57] | 13.33 (11.8x) [14.76] | 13.10 (11.6x) [14.41] |
| LEFT JOIN, ScalarDB on both sides | 0.44 (1.0x) [0.91] | 0.52 (1.2x) [1.13] | 0.51 (1.2x) [1.17] | 79.12 (180.3x) [89.11] | 0.57 (1.3x) [1.65] | 0.57 (1.3x) [1.11] |
| COUNT(*) of 100k rows | 3.01 (1.0x) [4.20] | 20.38 (6.8x) [22.13] | 12.13 (4.0x) [13.28] | 18.94 (6.3x) [20.33] | 20.10 (6.7x) [21.63] | 12.09 (4.0x) [13.03] |
| non-key filter JOIN FK, both ScalarDB | 0.80 (1.0x) [0.94] | 84.11 (105.3x) [96.80] | 46.35 (58.0x) [47.46] | 394.58 (493.9x) [423.10] | 13.11 (16.4x) [14.89] | 13.07 (16.4x) [14.52] |

### MYSQL

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ |
|---|---|---|---|---|---|---|
| key lookup | 0.62 (1.0x) [0.80] | 0.62 (1.0x) [0.86] | 0.56 (0.9x) [0.70] | 0.49 (0.8x) [0.56] | 0.49 (0.8x) [0.58] | 0.50 (0.8x) [0.59] |
| non-key filter (100 rows) | 0.54 (1.0x) [0.73] | 0.90 (1.7x) [1.15] | 0.89 (1.6x) [1.02] | 69.16 (128.4x) [70.76] | 0.89 (1.7x) [1.67] | 0.74 (1.4x) [1.00] |
| FK filter, ORDER BY, LIMIT 3 | 0.49 (1.0x) [0.64] | 0.64 (1.3x) [0.77] | 0.63 (1.3x) [0.77] | 190.06 (384.7x) [222.70] | 0.66 (1.3x) [1.09] | 0.71 (1.4x) [1.68] |
| COUNT by FK | 0.53 (1.0x) [1.13] | 0.66 (1.2x) [1.01] | 0.64 (1.2x) [1.27] | 360.94 (681.0x) [362.97] | 0.73 (1.4x) [1.47] | 0.66 (1.2x) [1.39] |
| plain table JOIN on key (10 rows) | 0.45 (1.0x) [0.81] | 105.50 (236.3x) [109.28] | 68.77 (154.0x) [69.62] | 0.52 (1.2x) [1.25] | 0.54 (1.2x) [1.11] | 0.52 (1.2x) [1.12] |
| key JOIN FK, both ScalarDB | 0.43 (1.0x) [0.53] | 787.67 (1818.6x) [831.60] | 558.32 (1289.1x) [578.50] | 359.74 (830.6x) [363.22] | 0.87 (2.0x) [1.18] | 0.75 (1.7x) [1.05] |
| plain table JOIN on non-key (1000 rows) | 0.93 (1.0x) [1.12] | 102.44 (110.7x) [105.16] | 65.41 (70.7x) [68.04] | 136.88 (147.9x) [140.07] | 3.49 (3.8x) [5.21] | 11.41 (12.3x) [13.07] |
| IN subquery on non-key | 1.60 (1.0x) [3.94] | 382.57 (238.6x) [406.84] | 174.68 (109.0x) [176.08] | 461.03 (287.6x) [527.98] | 2.67 (1.7x) [4.58] | 110.95 (69.2x) [114.93] |
| LEFT JOIN, ScalarDB on both sides | 0.53 (1.0x) [1.10] | 790.67 (1495.7x) [818.74] | 578.04 (1093.5x) [583.59] | 376.62 (712.5x) [378.44] | 0.94 (1.8x) [1.50] | 0.89 (1.7x) [1.27] |
| COUNT(*) of 100k rows | 7.34 (1.0x) [8.31] | 74.35 (10.1x) [76.99] | 38.05 (5.2x) [38.83] | 70.22 (9.6x) [71.70] | 71.00 (9.7x) [72.40] | 28.52 (3.9x) [29.83] |
| non-key filter JOIN FK, both ScalarDB | 1.68 (1.0x) [2.94] | 799.45 (475.2x) [799.93] | 577.22 (343.1x) [596.85] | 561.35 (333.7x) [562.72] | 3.13 (1.9x) [4.84] | 57.29 (34.1x) [58.16] |

### MARIADB

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ |
|---|---|---|---|---|---|---|
| key lookup | 0.47 (1.0x) [0.96] | 0.49 (1.0x) [0.61] | 0.52 (1.1x) [0.64] | 0.46 (1.0x) [0.52] | 0.45 (1.0x) [0.73] | 0.50 (1.1x) [0.77] |
| non-key filter (100 rows) | 0.48 (1.0x) [0.65] | 0.79 (1.6x) [1.37] | 0.88 (1.8x) [1.48] | 66.84 (139.4x) [68.56] | 0.78 (1.6x) [1.38] | 0.69 (1.4x) [0.98] |
| FK filter, ORDER BY, LIMIT 3 | 0.45 (1.0x) [0.65] | 0.61 (1.4x) [0.98] | 0.61 (1.4x) [0.71] | 177.96 (393.1x) [214.69] | 0.63 (1.4x) [1.08] | 0.65 (1.4x) [0.94] |
| COUNT by FK | 0.46 (1.0x) [0.64] | 0.61 (1.3x) [0.81] | 0.67 (1.4x) [1.00] | 348.00 (752.4x) [350.13] | 0.58 (1.2x) [0.72] | 0.66 (1.4x) [1.12] |
| plain table JOIN on key (10 rows) | 0.47 (1.0x) [1.06] | 83.07 (176.1x) [105.79] | 43.68 (92.6x) [45.59] | 0.53 (1.1x) [1.16] | 0.50 (1.1x) [0.56] | 0.52 (1.1x) [0.58] |
| key JOIN FK, both ScalarDB | 0.46 (1.0x) [0.97] | 0.69 (1.5x) [1.17] | 0.71 (1.5x) [0.93] | 339.13 (737.4x) [364.32] | 0.70 (1.5x) [1.05] | 0.77 (1.7x) [1.14] |
| plain table JOIN on non-key (1000 rows) | 1.28 (1.0x) [2.19] | 80.05 (62.7x) [82.16] | 40.74 (31.9x) [48.78] | 183.59 (143.8x) [185.70] | 1.88 (1.5x) [3.05] | 1.69 (1.3x) [2.79] |
| IN subquery on non-key | 1.11 (1.0x) [1.73] | 1185.42 (1070.4x) [1207.13] | 944.43 (852.8x) [978.03] | 446.35 (403.0x) [450.85] | 2.76 (2.5x) [3.57] | 2.21 (2.0x) [3.08] |
| LEFT JOIN, ScalarDB on both sides | 0.47 (1.0x) [1.70] | 400.24 (856.3x) [403.02] | 175.85 (376.3x) [221.87] | 362.69 (776.0x) [363.19] | 0.90 (1.9x) [2.35] | 0.98 (2.1x) [1.40] |
| COUNT(*) of 100k rows | 7.61 (1.0x) [8.30] | 77.84 (10.2x) [79.93] | 38.96 (5.1x) [40.23] | 65.47 (8.6x) [68.18] | 71.64 (9.4x) [73.15] | 27.34 (3.6x) [29.27] |
| non-key filter JOIN FK, both ScalarDB | 1.09 (1.0x) [1.85] | 1208.43 (1110.6x) [1209.37] | 937.80 (861.9x) [947.80] | 541.79 (497.9x) [572.97] | 2.35 (2.2x) [3.35] | 1.81 (1.7x) [2.22] |

### SQLSERVER

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ |
|---|---|---|---|---|---|---|
| key lookup | 0.74 (1.0x) [1.31] | 0.81 (1.1x) [1.68] | 0.77 (1.0x) [1.51] | 0.72 (1.0x) [1.46] | 0.71 (1.0x) [2.27] | 0.68 (0.9x) [1.21] |
| non-key filter (100 rows) | 0.63 (1.0x) [0.99] | 1.24 (2.0x) [3.53] | 0.96 (1.5x) [1.33] | 38.24 (60.6x) [41.20] | 1.08 (1.7x) [2.11] | 0.96 (1.5x) [1.48] |
| FK filter, ORDER BY, LIMIT 3 | 0.64 (1.0x) [0.85] | 0.94 (1.5x) [1.74] | 0.96 (1.5x) [1.41] | 140.81 (218.9x) [161.14] | 0.94 (1.5x) [1.41] | 0.95 (1.5x) [1.53] |
| COUNT by FK | 0.69 (1.0x) [1.59] | 0.95 (1.4x) [1.69] | 1.01 (1.5x) [1.84] | 85.50 (123.4x) [98.25] | 0.92 (1.3x) [1.33] | 0.92 (1.3x) [1.23] |
| plain table JOIN on key (10 rows) | 0.65 (1.0x) [1.27] | 0.71 (1.1x) [1.21] | 0.66 (1.0x) [0.84] | 0.66 (1.0x) [0.92] | 0.64 (1.0x) [0.85] | 0.69 (1.1x) [1.38] |
| key JOIN FK, both ScalarDB | 0.61 (1.0x) [1.18] | 1.02 (1.7x) [1.68] | 1.06 (1.7x) [2.83] | 265.16 (433.4x) [268.38] | 1.41 (2.3x) [2.58] | 1.53 (2.5x) [3.20] |
| plain table JOIN on non-key (1000 rows) | 1.44 (1.0x) [3.15] | 46.25 (32.2x) [48.69] | 2.44 (1.7x) [4.67] | 54.98 (38.3x) [56.54] | 12.74 (8.9x) [14.07] | 2.24 (1.6x) [3.00] |
| IN subquery on non-key | 1.32 (1.0x) [2.10] | 15.14 (11.4x) [20.97] | 27.61 (20.8x) [28.93] | 65.96 (49.8x) [73.90] | 3.80 (2.9x) [4.88] | 3.38 (2.6x) [6.12] |
| LEFT JOIN, ScalarDB on both sides | 0.60 (1.0x) [0.77] | 0.91 (1.5x) [1.28] | 0.96 (1.6x) [1.40] | 86.72 (144.6x) [97.35] | 1.43 (2.4x) [2.10] | 1.48 (2.5x) [2.08] |
| COUNT(*) of 100k rows | 7.51 (1.0x) [8.97] | 36.71 (4.9x) [37.59] | 11.70 (1.6x) [12.76] | 38.55 (5.1x) [39.87] | 36.75 (4.9x) [40.11] | 11.84 (1.6x) [13.54] |
| non-key filter JOIN FK, both ScalarDB | 1.74 (1.0x) [2.76] | 13.97 (8.0x) [18.60] | 27.79 (16.0x) [29.06] | 84.67 (48.6x) [92.66] | 4.29 (2.5x) [6.42] | 3.96 (2.3x) [5.77] |

### ORACLE

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ |
|---|---|---|---|---|---|---|
| key lookup | 0.51 (1.0x) [0.95] | 0.50 (1.0x) [0.72] | 0.50 (1.0x) [0.90] | 0.44 (0.9x) [0.76] | 0.42 (0.8x) [0.68] | 0.45 (0.9x) [0.79] |
| non-key filter (100 rows) | 1.62 (1.0x) [2.42] | 4.61 (2.8x) [8.30] | 2.05 (1.3x) [3.54] | 18.59 (11.5x) [20.92] | 4.32 (2.7x) [5.81] | 1.93 (1.2x) [2.38] |
| FK filter, ORDER BY, LIMIT 3 | 0.44 (1.0x) [0.51] | 0.52 (1.2x) [0.60] | 0.62 (1.4x) [0.83] | 101.05 (231.9x) [121.12] | 0.54 (1.2x) [0.91] | 0.64 (1.5x) [0.93] |
| COUNT by FK | 0.42 (1.0x) [0.56] | 0.50 (1.2x) [1.14] | 0.57 (1.4x) [1.17] | 63.70 (153.2x) [68.83] | 0.54 (1.3x) [0.88] | 0.62 (1.5x) [0.92] |
| plain table JOIN on key (10 rows) | 0.83 (1.0x) [1.11] | 0.90 (1.1x) [3.28] | 0.85 (1.0x) [1.03] | 0.83 (1.0x) [0.92] | 1.23 (1.5x) [1.51] | 1.41 (1.7x) [2.32] |
| key JOIN FK, both ScalarDB | 0.43 (1.0x) [0.49] | 0.55 (1.3x) [0.67] | 0.67 (1.6x) [0.79] | 115.17 (268.9x) [124.09] | 0.60 (1.4x) [1.26] | 0.88 (2.0x) [1.91] |
| plain table JOIN on non-key (1000 rows) | 3.81 (1.0x) [6.05] | 26.36 (6.9x) [29.67] | 12.06 (3.2x) [15.51] | 21.88 (5.7x) [27.49] | 7.82 (2.1x) [13.82] | 6.05 (1.6x) [10.62] |
| IN subquery on non-key | 12.49 (1.0x) [14.68] | 135.87 (10.9x) [142.14] | 31.26 (2.5x) [33.87] | 109.45 (8.8x) [130.77] | 26.65 (2.1x) [28.36] | 4.76 (0.4x) [7.70] |
| LEFT JOIN, ScalarDB on both sides | 0.43 (1.0x) [0.83] | 0.65 (1.5x) [1.22] | 0.67 (1.6x) [0.90] | 108.71 (254.3x) [142.91] | 0.57 (1.3x) [0.97] | 0.67 (1.6x) [0.91] |
| COUNT(*) of 100k rows | 1.38 (1.0x) [1.77] | 15.39 (11.2x) [17.03] | 4.83 (3.5x) [5.58] | 11.36 (8.2x) [12.89] | 15.18 (11.0x) [17.55] | 5.20 (3.8x) [5.96] |
| non-key filter JOIN FK, both ScalarDB | 12.81 (1.0x) [14.54] | 140.50 (11.0x) [159.54] | 31.17 (2.4x) [34.13] | 141.61 (11.1x) [145.92] | 18.59 (1.5x) [20.92] | 5.11 (0.4x) [7.39] |


## Full scans and further committed-arm forms (2026-10-11)

SPLIT_NI: `NOT IN (in-flight keys)`; SPLIT_HJ: left join to the in-flight keys; SPLIT_HD: the same
with `SELECT DISTINCT`, which keeps MySQL and MariaDB from merging it back into a join on the whole
metadata table; SPLIT_PA, SPLIT_PH: SPLIT_AJ and SPLIT_HD with the aggregate computed per arm.

### POSTGRESQL

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ | SPLIT_NI | SPLIT_HJ | SPLIT_HD | SPLIT_PA | SPLIT_PH |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full scan: COUNT(*) of 100k rows | 3.83 (1.0x) [5.04] | 21.60 (5.6x) [22.99] | 8.68 (2.3x) [9.80] | 20.25 (5.3x) [22.07] | 21.50 (5.6x) [22.96] | 8.39 (2.2x) [9.28] | 12.65 (3.3x) [13.54] | 8.40 (2.2x) [9.29] | 8.35 (2.2x) [9.16] | 6.85 (1.8x) [7.95] | 6.89 (1.8x) [8.09] |
| full scan: SUM of 500k rows | 17.42 (1.0x) [20.00] | 82.31 (4.7x) [89.55] | 25.35 (1.5x) [27.30] | 84.07 (4.8x) [91.35] | 82.15 (4.7x) [88.20] | 23.75 (1.4x) [25.38] | 27.70 (1.6x) [29.36] | 23.81 (1.4x) [25.48] | 23.95 (1.4x) [26.56] | 26.87 (1.5x) [28.02] | 26.77 (1.5x) [29.34] |
| full scan: GROUP BY of 100k rows | 9.12 (1.0x) [10.87] | 28.42 (3.1x) [30.70] | 15.00 (1.6x) [17.56] | 29.17 (3.2x) [31.61] | 26.65 (2.9x) [28.21] | 13.25 (1.5x) [15.86] | 16.35 (1.8x) [17.79] | 13.20 (1.4x) [14.77] | 13.05 (1.4x) [14.40] | 13.03 (1.4x) [14.97] | 14.28 (1.6x) [15.83] |
| full scan: unindexed filter | 4.74 (1.0x) [5.78] | 4.97 (1.0x) [5.47] | 4.98 (1.0x) [5.49] | 22.51 (4.7x) [26.49] | 4.94 (1.0x) [5.80] | 5.00 (1.1x) [5.90] | 9.92 (2.1x) [11.33] | 4.96 (1.0x) [5.51] | 4.95 (1.0x) [5.59] | 4.98 (1.0x) [6.03] | 5.05 (1.1x) [5.95] |

### SQLSERVER

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ | SPLIT_NI | SPLIT_HJ | SPLIT_HD | SPLIT_PA | SPLIT_PH |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full scan: COUNT(*) of 100k rows | 8.36 (1.0x) [10.25] | 38.02 (4.5x) [39.22] | 12.74 (1.5x) [14.07] | 39.24 (4.7x) [40.64] | 38.01 (4.5x) [39.40] | 12.58 (1.5x) [13.72] | 12.88 (1.5x) [16.62] | 14.92 (1.8x) [16.07] | 14.95 (1.8x) [16.42] | 12.07 (1.4x) [13.27] | 14.59 (1.7x) [16.10] |
| full scan: SUM of 500k rows | 70.14 (1.0x) [71.82] | 170.07 (2.4x) [175.58] | 82.86 (1.2x) [103.37] | 236.62 (3.4x) [242.27] | 170.07 (2.4x) [222.35] | 83.34 (1.2x) [84.85] | 83.33 (1.2x) [103.41] | 89.68 (1.3x) [91.32] | 90.09 (1.3x) [93.08] | 82.28 (1.2x) [83.34] | 90.50 (1.3x) [93.30] |
| full scan: GROUP BY of 100k rows | 12.08 (1.0x) [13.18] | 54.08 (4.5x) [55.57] | 28.48 (2.4x) [31.98] | 63.97 (5.3x) [66.33] | 54.46 (4.5x) [60.27] | 28.59 (2.4x) [30.62] | 28.72 (2.4x) [30.58] | 31.46 (2.6x) [37.04] | 31.30 (2.6x) [33.50] | 27.89 (2.3x) [30.43] | 30.59 (2.5x) [32.11] |
| full scan: unindexed filter | 13.56 (1.0x) [14.46] | 13.61 (1.0x) [14.72] | 13.83 (1.0x) [16.30] | 45.47 (3.4x) [46.57] | 13.67 (1.0x) [14.73] | 13.70 (1.0x) [15.05] | 13.58 (1.0x) [14.60] | 13.55 (1.0x) [14.60] | 13.79 (1.0x) [15.35] | 13.53 (1.0x) [14.52] | 13.44 (1.0x) [14.38] |

### ORACLE

Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ | SPLIT_NI | SPLIT_HJ | SPLIT_HD | SPLIT_PA | SPLIT_PH |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full scan: COUNT(*) of 100k rows | 1.58 (1.0x) [2.24] | 15.71 (9.9x) [17.10] | 5.10 (3.2x) [6.81] | 11.82 (7.5x) [12.82] | 15.58 (9.9x) [17.59] | 4.81 (3.0x) [5.95] | 4.92 (3.1x) [5.75] | 5.59 (3.5x) [6.62] | 5.51 (3.5x) [6.57] | 3.88 (2.5x) [4.92] | 4.67 (3.0x) [5.58] |
| full scan: SUM of 500k rows | 10.30 (1.0x) [11.85] | 107.97 (10.5x) [123.50] | 28.26 (2.7x) [29.73] | 67.78 (6.6x) [70.26] | 111.88 (10.9x) [141.31] | 28.36 (2.8x) [29.58] | 28.36 (2.8x) [29.58] | 31.30 (3.0x) [36.60] | 30.77 (3.0x) [31.99] | 15.13 (1.5x) [16.82] | 16.05 (1.6x) [17.33] |
| full scan: GROUP BY of 100k rows | 7.62 (1.0x) [12.38] | 26.79 (3.5x) [29.58] | 14.49 (1.9x) [19.34] | 20.12 (2.6x) [24.75] | 26.91 (3.5x) [30.52] | 14.21 (1.9x) [18.92] | 14.12 (1.9x) [17.04] | 15.09 (2.0x) [18.70] | 15.05 (2.0x) [19.67] | 10.28 (1.3x) [14.83] | 10.46 (1.4x) [13.49] |
| full scan: unindexed filter | 1.53 (1.0x) [1.84] | 1.71 (1.1x) [2.90] | 1.70 (1.1x) [2.39] | 16.55 (10.8x) [18.39] | 1.68 (1.1x) [2.24] | 1.73 (1.1x) [2.29] | 1.67 (1.1x) [2.33] | 1.67 (1.1x) [2.25] | 1.68 (1.1x) [2.43] | 1.65 (1.1x) [2.12] | 1.68 (1.1x) [2.27] |

### MYSQL

Whole suite with every form. Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ | SPLIT_NI | SPLIT_HJ | SPLIT_HD | SPLIT_PA | SPLIT_PH |
|---|---|---|---|---|---|---|---|---|---|---|---|
| key lookup | 0.63 (1.0x) [1.32] | 0.67 (1.1x) [1.32] | 0.67 (1.1x) [1.46] | 0.59 (0.9x) [1.41] | 0.57 (0.9x) [1.17] | 0.52 (0.8x) [0.93] | 0.50 (0.8x) [0.65] | 0.49 (0.8x) [0.61] | 0.53 (0.8x) [0.67] | 0.53 (0.9x) [0.93] | 0.56 (0.9x) [0.71] |
| non-key filter (100 rows) | 0.56 (1.0x) [0.76] | 0.86 (1.5x) [1.05] | 0.85 (1.5x) [1.03] | 69.71 (124.6x) [71.79] | 0.84 (1.5x) [1.70] | 0.70 (1.3x) [0.82] | 0.80 (1.4x) [1.37] | 0.77 (1.4x) [1.55] | 0.72 (1.3x) [1.60] | 0.84 (1.5x) [1.97] | 0.78 (1.4x) [1.46] |
| FK filter, ORDER BY, LIMIT 3 | 0.50 (1.0x) [0.61] | 0.62 (1.2x) [0.75] | 0.66 (1.3x) [0.94] | 188.95 (378.2x) [219.80] | 0.62 (1.2x) [0.93] | 0.62 (1.2x) [0.76] | 0.59 (1.2x) [0.70] | 0.60 (1.2x) [0.81] | 0.63 (1.3x) [0.78] | 0.59 (1.2x) [0.73] | 0.65 (1.3x) [1.06] |
| COUNT by FK | 0.45 (1.0x) [0.94] | 0.60 (1.3x) [0.95] | 0.63 (1.4x) [1.15] | 361.11 (806.6x) [382.29] | 0.65 (1.5x) [2.33] | 0.60 (1.3x) [1.13] | 0.59 (1.3x) [0.93] | 0.61 (1.4x) [1.25] | 0.65 (1.4x) [0.90] | 0.62 (1.4x) [1.44] | 0.61 (1.4x) [1.31] |
| plain table JOIN on key (10 rows) | 0.44 (1.0x) [0.63] | 104.68 (235.3x) [106.68] | 68.30 (153.5x) [72.68] | 0.53 (1.2x) [1.23] | 0.51 (1.2x) [0.78] | 0.57 (1.3x) [1.32] | 0.59 (1.3x) [1.02] | 0.57 (1.3x) [1.15] | 0.56 (1.3x) [0.87] | 0.52 (1.2x) [0.84] | 0.52 (1.2x) [0.77] |
| key JOIN FK, both ScalarDB | 0.44 (1.0x) [0.93] | 790.35 (1792.9x) [792.36] | 562.48 (1275.9x) [573.61] | 359.78 (816.1x) [384.34] | 0.86 (1.9x) [1.44] | 0.78 (1.8x) [1.52] | 0.75 (1.7x) [1.29] | 0.76 (1.7x) [1.40] | 0.87 (2.0x) [1.55] | 0.79 (1.8x) [1.19] | 0.88 (2.0x) [1.67] |
| plain table JOIN on non-key (1000 rows) | 1.08 (1.0x) [2.76] | 103.57 (96.1x) [106.53] | 66.40 (61.6x) [69.23] | 136.97 (127.1x) [140.03] | 1.79 (1.7x) [2.69] | 11.37 (10.6x) [15.02] | 11.35 (10.5x) [14.63] | 3.60 (3.3x) [5.91] | 2.42 (2.2x) [5.06] | 11.34 (10.5x) [13.75] | 1.72 (1.6x) [3.11] |
| IN subquery on non-key | 0.79 (1.0x) [1.01] | 379.13 (478.6x) [404.24] | 173.15 (218.6x) [178.63] | 459.70 (580.3x) [460.69] | 2.92 (3.7x) [4.60] | 93.99 (118.7x) [116.07] | 93.52 (118.1x) [96.50] | 4.68 (5.9x) [6.50] | 2.50 (3.2x) [4.59] | 93.95 (118.6x) [106.08] | 2.41 (3.0x) [4.21] |
| LEFT JOIN, ScalarDB on both sides | 0.53 (1.0x) [1.26] | 789.10 (1486.9x) [831.90] | 562.13 (1059.2x) [563.57] | 363.85 (685.6x) [410.34] | 0.89 (1.7x) [2.20] | 0.91 (1.7x) [2.20] | 0.87 (1.6x) [1.37] | 0.94 (1.8x) [1.85] | 1.07 (2.0x) [1.76] | 0.91 (1.7x) [1.69] | 1.08 (2.0x) [2.12] |
| full scan: COUNT(*) of 100k rows | 6.40 (1.0x) [7.45] | 71.52 (11.2x) [74.27] | 36.28 (5.7x) [38.04] | 66.76 (10.4x) [67.82] | 68.98 (10.8x) [76.71] | 27.52 (4.3x) [29.36] | 28.90 (4.5x) [31.50] | 72.07 (11.3x) [72.91] | 13.67 (2.1x) [15.23] | 24.95 (3.9x) [26.60] | 10.26 (1.6x) [12.47] |
| non-key filter JOIN FK, both ScalarDB | 1.30 (1.0x) [3.50] | 789.31 (606.0x) [807.24] | 567.35 (435.6x) [570.42] | 558.79 (429.0x) [582.62] | 2.95 (2.3x) [5.39] | 57.28 (44.0x) [58.43] | 57.34 (44.0x) [61.63] | 3.11 (2.4x) [5.46] | 2.89 (2.2x) [5.31] | 57.49 (44.1x) [58.89] | 2.52 (1.9x) [4.26] |
| full scan: SUM of 500k rows | 44.52 (1.0x) [48.02] | 374.64 (8.4x) [375.38] | 161.58 (3.6x) [187.09] | 376.22 (8.5x) [379.73] | 364.81 (8.2x) [367.80] | 151.61 (3.4x) [171.63] | 152.17 (3.4x) [158.47] | 375.67 (8.4x) [382.53] | 78.60 (1.8x) [79.70] | 133.32 (3.0x) [135.33] | 61.45 (1.4x) [62.74] |
| full scan: GROUP BY of 100k rows | 11.34 (1.0x) [13.16] | 83.60 (7.4x) [103.84] | 48.31 (4.3x) [50.15] | 85.76 (7.6x) [89.10] | 82.44 (7.3x) [85.58] | 39.28 (3.5x) [40.74] | 38.81 (3.4x) [41.87] | 82.98 (7.3x) [87.32] | 24.09 (2.1x) [29.33] | 28.91 (2.5x) [31.55] | 21.80 (1.9x) [24.37] |
| full scan: unindexed filter | 12.36 (1.0x) [14.48] | 14.07 (1.1x) [15.69] | 25.27 (2.0x) [27.25] | 69.81 (5.6x) [72.17] | 12.88 (1.0x) [14.94] | 23.96 (1.9x) [25.74] | 23.97 (1.9x) [25.43] | 12.82 (1.0x) [15.55] | 12.52 (1.0x) [14.18] | 24.05 (1.9x) [26.27] | 12.49 (1.0x) [15.04] |

### MARIADB

Whole suite with every form. Median ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows that differ from BASE.

| query | BASE | VIEW_LJ | VIEW_AJ | VIEW_CASE | SPLIT_LJ | SPLIT_AJ | SPLIT_NI | SPLIT_HJ | SPLIT_HD | SPLIT_PA | SPLIT_PH |
|---|---|---|---|---|---|---|---|---|---|---|---|
| key lookup | 0.44 (1.0x) [0.83] | 0.50 (1.1x) [0.93] | 0.52 (1.2x) [0.92] | 0.54 (1.2x) [1.07] | 0.54 (1.2x) [1.23] | 0.49 (1.1x) [0.96] | 0.50 (1.1x) [1.29] | 0.50 (1.1x) [1.10] | 0.50 (1.1x) [0.71] | 0.46 (1.0x) [0.58] | 0.49 (1.1x) [0.56] |
| non-key filter (100 rows) | 0.46 (1.0x) [0.55] | 0.81 (1.7x) [1.44] | 0.81 (1.8x) [1.60] | 67.51 (146.1x) [70.03] | 0.77 (1.7x) [1.68] | 0.66 (1.4x) [1.77] | 0.68 (1.5x) [1.16] | 0.74 (1.6x) [1.67] | 0.71 (1.5x) [1.37] | 0.75 (1.6x) [2.16] | 0.70 (1.5x) [1.53] |
| FK filter, ORDER BY, LIMIT 3 | 0.49 (1.0x) [0.93] | 0.60 (1.2x) [0.88] | 0.63 (1.3x) [0.82] | 168.80 (345.2x) [206.79] | 0.61 (1.2x) [0.97] | 0.65 (1.3x) [1.13] | 0.63 (1.3x) [1.44] | 0.60 (1.2x) [1.03] | 0.63 (1.3x) [0.76] | 0.65 (1.3x) [1.34] | 0.65 (1.3x) [1.73] |
| COUNT by FK | 0.43 (1.0x) [0.51] | 0.60 (1.4x) [0.75] | 0.63 (1.5x) [1.21] | 349.13 (811.7x) [374.86] | 0.62 (1.4x) [1.15] | 0.65 (1.5x) [1.19] | 0.63 (1.5x) [1.54] | 0.65 (1.5x) [1.09] | 0.65 (1.5x) [1.93] | 0.66 (1.5x) [1.15] | 0.67 (1.6x) [1.36] |
| plain table JOIN on key (10 rows) | 0.48 (1.0x) [1.12] | 83.96 (173.2x) [88.08] | 45.15 (93.2x) [46.92] | 0.52 (1.1x) [0.98] | 0.53 (1.1x) [0.70] | 0.53 (1.1x) [0.79] | 0.52 (1.1x) [0.98] | 0.51 (1.1x) [0.93] | 0.54 (1.1x) [1.01] | 0.55 (1.1x) [1.19] | 0.59 (1.2x) [1.85] |
| key JOIN FK, both ScalarDB | 0.45 (1.0x) [0.82] | 0.67 (1.5x) [1.34] | 0.73 (1.6x) [1.78] | 342.11 (755.9x) [356.05] | 0.76 (1.7x) [1.51] | 0.76 (1.7x) [1.22] | 0.78 (1.7x) [1.55] | 0.78 (1.7x) [1.37] | 0.86 (1.9x) [2.31] | 0.75 (1.7x) [1.73] | 0.81 (1.8x) [1.26] |
| plain table JOIN on non-key (1000 rows) | 1.45 (1.0x) [3.69] | 79.69 (54.9x) [82.10] | 41.24 (28.4x) [44.07] | 181.97 (125.3x) [185.44] | 2.26 (1.6x) [3.74] | 1.84 (1.3x) [3.60] | 2.45 (1.7x) [5.11] | 2.75 (1.9x) [4.60] | 2.15 (1.5x) [3.70] | 1.49 (1.0x) [3.23] | 1.44 (1.0x) [2.85] |
| IN subquery on non-key | 1.01 (1.0x) [1.67] | 1208.45 (1201.7x) [1225.02] | 970.60 (965.2x) [994.66] | 448.32 (445.8x) [452.05] | 2.75 (2.7x) [4.40] | 1.95 (1.9x) [2.86] | 2.30 (2.3x) [3.69] | 3.20 (3.2x) [4.60] | 2.13 (2.1x) [3.84] | 2.36 (2.3x) [3.80] | 2.24 (2.2x) [3.85] |
| LEFT JOIN, ScalarDB on both sides | 0.48 (1.0x) [1.07] | 403.70 (835.2x) [425.02] | 174.28 (360.6x) [177.03] | 368.16 (761.6x) [395.27] | 0.91 (1.9x) [1.72] | 0.91 (1.9x) [1.17] | 0.93 (1.9x) [1.57] | 1.00 (2.1x) [1.77] | 1.06 (2.2x) [2.16] | 0.95 (2.0x) [1.74] | 1.06 (2.2x) [1.83] |
| full scan: COUNT(*) of 100k rows | 7.59 (1.0x) [9.41] | 77.48 (10.2x) [96.60] | 38.89 (5.1x) [42.73] | 66.11 (8.7x) [67.56] | 71.52 (9.4x) [74.15] | 26.53 (3.5x) [27.72] | 26.57 (3.5x) [30.92] | 76.16 (10.0x) [82.12] | 24.19 (3.2x) [25.55] | 19.30 (2.5x) [21.38] | 16.52 (2.2x) [17.32] |
| non-key filter JOIN FK, both ScalarDB | 1.06 (1.0x) [2.39] | 1093.59 (1029.8x) [1126.00] | 813.40 (765.9x) [819.82] | 528.88 (498.0x) [532.40] | 2.54 (2.4x) [4.09] | 2.23 (2.1x) [3.31] | 2.11 (2.0x) [3.64] | 3.04 (2.9x) [4.61] | 1.95 (1.8x) [4.13] | 1.95 (1.8x) [3.09] | 2.20 (2.1x) [3.58] |
| full scan: SUM of 500k rows | 39.80 (1.0x) [43.42] | 408.46 (10.3x) [409.11] | 180.82 (4.5x) [228.59] | 350.58 (8.8x) [361.79] | 360.62 (9.1x) [362.07] | 136.88 (3.4x) [139.78] | 134.79 (3.4x) [136.12] | 385.16 (9.7x) [401.03] | 125.19 (3.1x) [127.01] | 99.22 (2.5x) [100.63] | 87.89 (2.2x) [88.78] |
| full scan: GROUP BY of 100k rows | 8.61 (1.0x) [9.49] | 83.67 (9.7x) [106.85] | 45.04 (5.2x) [47.61] | 75.11 (8.7x) [78.70] | 76.72 (8.9x) [79.48] | 31.82 (3.7x) [33.34] | 31.64 (3.7x) [33.90] | 80.81 (9.4x) [82.46] | 29.38 (3.4x) [31.08] | 20.67 (2.4x) [21.69] | 18.05 (2.1x) [19.01] |
| full scan: unindexed filter | 9.66 (1.0x) [10.59] | 10.67 (1.1x) [12.57] | 22.14 (2.3x) [23.16] | 64.10 (6.6x) [66.24] | 9.65 (1.0x) [10.39] | 21.05 (2.2x) [21.86] | 21.50 (2.2x) [22.53] | 9.69 (1.0x) [10.44] | 9.68 (1.0x) [10.18] | 20.93 (2.2x) [22.13] | 9.67 (1.0x) [10.43] |


## Joins returning many rows, and three or four ScalarDB tables (2026-10-11)

New shapes: `c` joined to `o` on the foreign key for the customers of 3, 30 or 300 regions
(1.5k, 15k, 150k rows); a third ScalarDB table `r` (1000 regions, 10 in flight) joined as
`r - c - o` (8 copies), and a fourth through a second reference to `o` (16 copies). Every rewrite
returned the rows of BASE. Medians in ms (ratio to BASE), the form chosen per engine; PostgreSQL with
the default settings and with `max_parallel_workers_per_gather = 0` set for the split statement.

| query | PostgreSQL AJ | PostgreSQL AJ, no parallel | MySQL HD | MariaDB HD | SQL Server AJ (HD) | Oracle AJ |
|---|---|---|---|---|---|---|
| JOIN FK, 1.5k rows | 2.84 (1.9x) | 2.38 (1.7x) | 3.07 (1.7x) | 2.63 (2.3x) | 4.50 (2.0x) | 19.70 (1.1x) |
| JOIN FK, 15k rows | 24.89 (1.0x) | 43.63 (1.1x) | 11.07 (1.3x) | 11.03 (1.6x) | 24.85 (2.0x) | 88.25 (1.2x) |
| JOIN FK, 150k rows | 110.00 (1.1x) | 102.29 (1.1x) | 100.46 (1.1x) | 95.27 (1.4x) | 129.58 (1.2x) | 617.49 (1.0x) |
| 3 tables, key lookup | 0.55 (1.3x) | 0.52 (1.3x) | 1.22 (2.3x) | 1.20 (2.6x) | 1.58 (2.6x) (1.7x) | 0.70 (1.6x) |
| 3 tables, one region (500 rows) | 12.55 (10.6x) | 2.28 (2.0x) | 2.34 (2.4x) | 2.38 (2.0x) | 3.99 (2.2x) | 6.14 (0.4x) |
| 4 tables, key lookup (25 rows) | 14.74 (27.7x) | 0.95 (1.9x) | 2.53 (5.2x) | 2.71 (5.2x) | 3.73 (5.2x) (2.4x) | 2.04 (1.5x) |
| 4 tables, one region (2.5k rows) | 16.77 (6.2x) | 13.46 (4.7x) | 7.60 (2.9x) | 5.80 (2.4x) | 63.05 (15.2x) (3.8x) | 26.20 (0.7x) |

Findings:

1. **Joins returning many rows stay at 1.0-2.3x**; the larger the result, the closer to BASE. Oracle
   scans `o` for BASE already from 1.5k rows, and the split is not slower there.
2. **Four ScalarDB tables reach 2-5x** on lookups and up to 4.7x on PostgreSQL with 2.5k rows. The
   16 copies cost planning (PostgreSQL: 14 ms to plan the 4-table split with a custom plan, 2 ms for
   3 tables, 0.2 ms for BASE) and execution: each copy with an in-flight `o` is driven from the
   region side and probes the in-flight index a few hundred times before finding nothing.
3. **SQL Server's NOT EXISTS degrades with four tables** (15x); the DISTINCT set (HD) holds at 3.8x.
4. **Parallel plans on PostgreSQL.** With `max_parallel_workers_per_gather = 0` for the split
   statement the cases at 12-28x fall to 1.3-2.0x (IN subquery 1.4x, non-key filter JOIN FK 1.3x),
   but the 500k-row SUM rises from 1.4x to 2.3x, where BASE gains from parallel workers.
   `parallel_setup_cost` of 10000 or 100000 behaves the same as turning parallel plans off. Generic
   plans (`plan_cache_mode = force_generic_plan`) bring the parallel plans back, and with parallel
   plans off they choose a scan for `region < ?` (16.8x on 1.5k rows).

## Parallel plans only when the plain query has one, and n + 1 copies (2026-10-11)

**PostgreSQL, automatic.** Before a split statement, the plain query is explained with the
parameters; when its plan has no `Gather`, `max_parallel_workers_per_gather = 0` is set for the
split statement. Whole suite: every query at 1.0-2.3x; the former 12-28x cases at 1.3-2.0x; the
500k-row SUM keeps its parallel plan (1.3x); the worst is COUNT(*) of 100k rows (2.3x, 1.9x per arm).

**n + 1 copies** (SPLIT_LT with NOT EXISTS, SPLIT_LH with the DISTINCT set): one copy with every
table committed, then per table t in order a copy with the tables before t committed, t in flight,
and each table after t in either image, joined per row through `CROSS JOIN LATERAL` or
`CROSS APPLY` and placed outwards from t so that each joins a table already placed. MariaDB has no
LATERAL, so it keeps 2^n copies.

| query | PostgreSQL 2^n / n+1 | MySQL HD 2^n / LH n+1 | MariaDB HD 2^n | SQL Server HD 2^n / LT n+1 | Oracle AJ 2^n / LT n+1 |
|---|---|---|---|---|---|
| 3 tables, key lookup | 1.3x / 1.2x | 1.9x / 1.7x | 2.3x | 1.6x / 2.1x | 1.5x / 1.8x |
| 3 tables, one region (500 rows) | 1.3x / 1.4x | 3.0x / 1.9x | 2.5x | 3.1x / 3.1x | 0.5x / 0.3x |
| 4 tables, key lookup (25 rows) | 1.9x / 1.4x | 3.8x / 2.8x | 5.0x | 2.3x / 3.3x | 1.9x / 1.5x |
| 4 tables, one region (2.5k rows) | 5.0x / 2.7x | 3.1x / 2.7x | 2.8x | 3.6x / 23.5x | 0.7x / 0.7x |

n + 1 copies help PostgreSQL and MySQL with four tables, change little on Oracle, and hurt SQL
Server (plan not yet examined).

## n + 1 copies with rows in flight: rejected (2026-10-11)

The timings above draw random customers and regions, so almost no run reads a row in flight and the
in-flight copies end at once. With `-Dnativejdbc.bench.hot=true` every run reads rows in flight
(customers 7, 1007, ...; region 7, all of whose customers are in flight, a worst case):

| query | PostgreSQL AJ / LT | MySQL HD / LH | MariaDB HD / LH | SQL Server HD / LT | Oracle AJ / LT |
|---|---|---|---|---|---|
| 3 tables, key lookup | 1.5x / 1.4x | 3.3x / 2.6x | 2.9x / 2.6x | 3.4x / 2.9x | 3.3x / 3.2x |
| 3 tables, one region | 1.7x / 3.4x | 2.3x / 7.7x | 2.1x / 822x | 9.1x / 23.0x | 1.1x / 3.4x |
| 4 tables, key lookup | 2.1x / 1.8x | 5.9x / 3.0x | 4.9x / 2.9x | 4.8x / 3.5x | 0.2x / 0.2x |
| 4 tables, one region | 3.7x / 5.4x | 3.3x / 8.9x | 2.3x / 2877x | 5.0x / 24.7x | 1.1x / 1.9x |

The n + 1 form loses as soon as rows are in flight. A table after the in-flight one is read in
either image, and its in-flight arm can only be joined through the before image, which has no index:
on PostgreSQL, MySQL, SQL Server and Oracle each outer row scans the table's in-flight rows (SQL
Server's plan: the in-flight index scanned 500 and 100 times for 25k and 10k rows); MariaDB,
without LATERAL, materialises the derived table: all 500k orders in the plan of the variant
that groups each arm, and the same order of time without grouping. The 2^n copies drive each
in-flight copy from the in-flight rows themselves, so they stay indexed. Decision: keep 2^n copies.

With rows in flight, SQL Server's 2^n copies also degrade: IN subquery 7x, non-key filter JOIN FK
6x, 3 tables by region 8-9x, 4 tables by region 5-11x (other engines 1.0-3.5x on these). Open.

## SQL Server with rows in flight (2026-10-11)

Plans (`SET STATISTICS PROFILE ON`, hot parameters) of the IN subquery, the non-key filter JOIN FK
and the 3-table region join show one cause: in the copy with the customers in flight and the orders
committed, the orders' `NOT EXISTS` runs as a nested-loop anti-semi-join that reads every in-flight
row of `om` per order (500 executions, 50,000 rows) instead of seeking the order's key. The plan is
costed for a copy expected to be nearly empty. A unique filtered index or a `(tx_state, id)` index
gives the same plan (hot: 6.4-9.8x on these queries).

Fix measured: in the copies with a table in flight, the committed tables use the left join on the
metadata (a primary-key lookup per row), which cannot degrade with the number of rows in flight;
the copy with every table committed keeps its form (SPLIT_MX with NOT EXISTS there, SPLIT_MH with
the DISTINCT set). Hot, SQL Server:

| query | AJ | HD | MX | MH |
|---|---|---|---|---|
| IN subquery | 6.6x | 6.5x | 2.9x | 3.0x |
| non-key filter JOIN FK | 5.6x | 5.8x | 2.8x | 2.9x |
| 3 tables, key lookup | 2.8x | 3.2x | 4.1x | 2.8x |
| 3 tables, one region | 5.5x | 5.6x | 2.8x | 3.0x |
| 4 tables, key lookup | 6.1x | 5.8x | 8.6x | 5.2x |
| 4 tables, one region | 10.3x | 4.9x | 10.7x | 7.1x |

On the other engines the mixed form gains nothing (PostgreSQL slightly worse, 2.4-2.8x against
1.5-2.1x on the IN subquery and the filtered join), so they keep their forms. Without rows in flight
the copies with a table in flight are empty, so the cold timings should not change (not
re-measured). Four SQL Server tables
stay at 5-7x hot. SQL Server runs under x86 emulation here, and its timings vary more than the
others'.

## A remote database: 1 ms round trip (2026-10-11)

`-Dnativejdbc.bench.rttMs=1` measures through `DelayProxy`, a TCP proxy in the benchmark that holds
every chunk for half the round trip in each direction (busy-waiting: parked threads woke up to
milliseconds late; `tc netem` in Docker Desktop's VM gave 1-17 ms for 1 ms). Waking the client
thread after a network wait still adds jitter of about a millisecond, as a real network does, so
the modes are measured interleaved: each round runs every mode once with the same parameters in a
rotating order. Every query is one round trip for both the plain query and the rewrite (counted at
the proxy). Ratios with the form chosen per engine, random parameters:

| shape | PostgreSQL AJ/PA | MySQL PH | MariaDB PH | SQL Server MH | Oracle PA |
|---|---|---|---|---|---|
| lookups and filters on one table | 1.0-1.1x | 1.0-1.2x | 1.1-1.4x | 1.0-1.4x | 1.0-1.1x |
| two-table joins, IN, LEFT JOIN | 1.0-1.3x | 1.2-1.6x | 1.2-1.5x | 1.1-1.8x | 0.5-1.2x |
| joins returning 1.5k-150k rows | 0.9-1.3x | 1.3-1.8x | 1.4x | 1.2-1.7x | 1.0-1.2x |
| 3 tables | 1.2-1.4x | 1.4-1.7x | 1.4-1.6x | 1.2-2.2x | 0.6-1.1x |
| 4 tables | 1.3x, 3.2x by region | 2.0-2.1x | 2.0x | 1.5x, 3.4x by region | 0.8-1.1x |
| full scans | 1.0-1.8x | 1.0-1.9x | 1.0-2.2x | 1.0-2.4x | 1.0-1.9x |

The round trip hides the rewrite's fixed costs (an extra index probe, a longer statement) on short
queries; full scans and large results, where the database's own work dominates, keep their local
ratios. PostgreSQL's figures for joins are with `max_parallel_workers_per_gather = 0` set once for
the session: setting and resetting it around every execution, as the automatic mode does, cost
about 1 ms per statement on top (4 tables by key 1.9x against 1.3x; presumably re-planning, not
verified), so the driver
should keep parallel plans off for the session and enable them only around statements whose plain
plan is parallel, which are long anyway. SQL Server's NOT EXISTS (AJ) in the copy with every table
committed reaches 13.5x on 4 tables by region remotely; the DISTINCT set (MH) holds 3.4x.

## Derived-table fragments (2026-10-11)

The driver should not edit the user's `WHERE` clause and column references per copy. Instead it
replaces each split table reference with a derived table that carries the copy's committed check
and exposes the copy's columns under their own names:

```sql
FROM (SELECT t.id, t.name, ... FROM customers t
      WHERE NOT EXISTS (SELECT 1 FROM customers_inflight i WHERE i.id = t.id)) c
```

`SPLIT_D*` modes are the `SPLIT_*` modes emitted this way. Cold and hot, the results match BASE
everywhere. Compared with the direct emission:

- PostgreSQL, Oracle, SQL Server: the same, within noise. Every form merges into the outer query.
- MySQL: the same, or faster (the HD form for joins: 2.7 ms to 1.7 ms).
- MariaDB, HD/PH (the chosen form): the same on scans and large joins; 0.05-0.5 ms slower on
  short queries (4 tables by key 2.3 ms to 2.9 ms).
- MariaDB, NOT EXISTS (AJ, MX, PA): the derived table is materialized. Full scans are 3-4x slower
  and joins over many rows 2.5x slower. MariaDB must not put NOT EXISTS inside a derived table,
  which it would not use anyway.

The driver therefore emits derived-table fragments.
