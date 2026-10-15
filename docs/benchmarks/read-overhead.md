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
7. **Through the driver** (last section): the driver's own SQL matches or beats the hand-written
   forms; routing a statement costs 0.3-2.6 ms before it reaches the server, so the statement
   cache (todo 008) is needed. Not measured: joins of five or more ScalarDB tables (32 copies
   and up).
8. **LEFT JOINs and subqueries through the driver** (milestone 3c, last section): the nullable side
   of a LEFT JOIN goes from 790-1850x through `rc_T` on MySQL and MariaDB to 1.2-2.3x on every
   engine. An IN or NOT EXISTS that went through `rc_T` at 93-1520x on PostgreSQL, MySQL and
   MariaDB is now 1.1-7.4x, except on MySQL: 26x for an IN over a non-key at 10,000 rows in
   flight, 16x for one over the key, and 13.6x for a NOT EXISTS at 100 rows in flight (todo 118).

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

## Row limits and DISTINCT inside each copy (2026-10-12)

The split read applies ORDER BY with a row limit, and DISTINCT, only on top of the copies. Pagination
then reads every matching row of every copy before the limit applies (todo 089). New shapes:

- `ORDER BY` the key `DESC`, 20 rows of `o` (500k), at offset 0 and 10,000;
- the same over the join of `c` and `o`;
- an indexed range of 30k rows of `c`, `ORDER BY` the key, 20 rows;
- `DISTINCT` over an indexed column (1,000 values in 100k rows).

New modes:

- `SPLIT_DRV`: the form the driver emits on the engine (NOT EXISTS on PostgreSQL and Oracle, the
  DISTINCT set on MySQL and MariaDB, MX on SQL Server), limit and DISTINCT on top only.
- `SPLIT_DRVP`: the same with `ORDER BY` and a limit of offset + n rows (or the DISTINCT) also inside
  each copy, which is wrapped as `SELECT * FROM (copy) pN`. The top keeps its own clauses.
- `SPLIT_DAJP`, `SPLIT_DHDP`, `SPLIT_DLJP`: SPLIT_DRVP with NOT EXISTS, the DISTINCT set, or the left
  join on the metadata as the committed form on every engine.
- `SPLIT_DAJN`: SPLIT_DAJP with `/*+ NO_SEMIJOIN() */` in the NOT EXISTS (MySQL).
- `SPLIT_DRVR`: Oracle, each copy limited by `WHERE ROWNUM <= n` around its ordered rows, not
  `FETCH FIRST`.

Every mode returned BASE's rows. Ratios to BASE, medians:

| shape | engine | DRV (now) | DRVP | best per-copy form |
|---|---|---|---|---|
| 20 rows of 500k | PostgreSQL | 40.8x | 1.3x | DRVP 1.3x |
| | MySQL | 162x | 150x | DLJP 1.3x, DAJN 1.3x |
| | MariaDB | 365x | 1.5x | DRVP 1.5x |
| | SQL Server | 1.5x | 1.5x | none needed |
| | Oracle | 36.0x | 25.1x | DRVR 1.1x |
| 20 rows after 10k | PostgreSQL | 11.4x | 1.2x | DRVP 1.2x |
| | MySQL | 80.1x | 77.0x | DLJP 5.7x |
| | MariaDB | 96.9x | 3.2x | DRVP 3.2x |
| | SQL Server | 1.2x | 1.2x | none needed |
| | Oracle | 36.1x | 31.7x | DRVR 18.4x |
| JOIN, 20 rows | PostgreSQL | 109x | 2.5x | DRVP 2.5x |
| | MySQL | 362x | 336x | DLJP 4.4x |
| | MariaDB | 1.9x | 1.6x | DRVP 1.6x (BASE itself 121 ms) |
| | SQL Server | 4.1x | 5.2x | none better |
| | Oracle | 95.8x | 42.0x | DRVR 2.1x |
| range of 30k, 20 rows | PostgreSQL | 10.3x | 1.4x | DAJP 1.2x |
| | MySQL | 34.1x | 34.9x | DLJP 1.8x |
| | MariaDB | 39.3x | 1.6x | DRVP 1.6x |
| | SQL Server | 1.0x | 1.2x | none needed |
| | Oracle | 4.3x | 3.8x | DRVR 1.2x |
| DISTINCT, 1,000 of 100k | PostgreSQL | 1.5x | 1.6x | none better |
| | MySQL | 15.2x | 14.3x | none better |
| | MariaDB | 23.4x | 18.1x | none good |
| | SQL Server | 2.6x | 2.5x | none better |
| | Oracle | 1.8x | 1.2x | DRVP 1.2x |

Findings:

- **A limit inside each copy brings pagination close to the native query** on PostgreSQL, MariaDB
  (with the engine's current form), MySQL and Oracle (each with its own form below). SQL Server
  needs nothing: its plan already stops early over the union (Top over Merge Concatenation).
- **MySQL needs a per-row committed check under a limit.** It turns NOT EXISTS into an anti-join
  against a materialized copy of the subquery and builds the DISTINCT set the same way; both scan
  all 500k metadata rows (52 ms) before the first row, so the limit saves nothing. The left join on
  the metadata (a primary-key lookup per row) or NOT EXISTS with `NO_SEMIJOIN` keeps the index order,
  and the copy stops after n rows.
- **Oracle needs `ROWNUM`.** `FETCH FIRST` inside a copy becomes a window function over the whole
  copy; `WHERE ROWNUM <= n` over the ordered copy is a stop key.
- **The left-join form is never safe on PostgreSQL**: 2021x on the join, 36x at offset 10k.
- **Large offsets stay expensive**: each copy returns offset + n rows (10,020 here), so offset 10k is
  1.2x on PostgreSQL but 3-18x elsewhere.
- **DISTINCT inside each copy helps only on Oracle**; on MySQL and MariaDB a DISTINCT over a split
  table stays 14-23x whatever the form (not resolved here).

## Many rows in flight (2026-10-12)

Every run so far held 100 rows in flight per table. Stuck rows (todo 085) or a large ScalarDB batch
can leave far more. The same shapes were run with 100, 10,000 and 100,000 rows in flight per
table (`-Dnativejdbc.bench.inflight=N`): 10k is 10% of `c` and 2% of `o`; 100k is 50% of `c` (the
cap) and 20% of `o`. The first 100 stay in flight at every count, so the hot customer and region
keep theirs. Every mode returned BASE's rows at every count.

Ratios to BASE, medians. The driver form is SPLIT_DRV: NOT EXISTS on PostgreSQL and Oracle, the
DISTINCT set on MySQL and MariaDB, MX on SQL Server (MH from three tables).

| shape | engine | driver form: 100 / 10k / 100k | NOT EXISTS: 100 / 10k / 100k |
|---|---|---|---|
| key lookup | PostgreSQL | 0.9x / 0.9x / 0.9x | same |
|  | MySQL | 1.1x / 10.3x / 38.3x | 0.9x / 1.1x / 0.9x |
|  | MariaDB | 1.2x / 4.8x / 18.6x | 1.1x / 1.2x / 1.1x |
|  | SQL Server | 1x / 1.1x / 1x | 1x / 1x / 1x |
|  | Oracle | 0.9x / 0.8x / 1x | same |
| non-key filter (100 rows) | PostgreSQL | 0.9x / 2.6x / 13.3x | same |
|  | MySQL | 1.2x / 21x / 71x | 1.4x / 13x / 26.9x |
|  | MariaDB | 1.4x / 16.8x / 164.6x | 1.8x / 13.4x / 145.2x |
|  | SQL Server | 1.4x / 34.6x / 31.2x | 1.5x / 34.4x / 31.1x |
|  | Oracle | 1.3x / 2.8x / 4x | same |
| FK filter, ORDER BY, LIMIT 3 | PostgreSQL | 1x / 2.6x / 19.2x | same |
|  | MySQL | 1.4x / 24x / 274.6x | 1.3x / 16.5x / 160.9x |
|  | MariaDB | 1.4x / 20.8x / 158.1x | 1.4x / 16.8x / 122.4x |
|  | SQL Server | 1.4x / 125.8x / 121x | 1.4x / 125.5x / 121.1x |
|  | Oracle | 1.3x / 5.8x / 15.5x | same |
| key JOIN FK, both ScalarDB | PostgreSQL | 1.2x / 3x / 22x | same |
|  | MySQL | 1.9x / 49.3x / 367x | 1.8x / 20.7x / 134.6x |
|  | MariaDB | 1.9x / 26.7x / 237.8x | 1.6x / 17x / 131.7x |
|  | SQL Server | 2x / 31.8x / 34.4x | 2x / 32x / 34.6x |
|  | Oracle | 1.5x / 6x / 16.9x | same |
| many rows: JOIN FK, 3k customers (15k rows) | PostgreSQL | 0.9x / 1.5x / 2x | same |
|  | MySQL | 1.3x / 9.8x / 54.8x | 16.6x / 19.9x / 38.3x |
|  | MariaDB | 2x / 9.8x / 54.4x | 4.7x / 10.7x / 47.1x |
|  | SQL Server | 1.5x / 18.2x / 9.4x | 2.1x / 16.1x / 8.7x |
|  | Oracle | 1.2x / 1.3x / 2.4x | same |
| 4 tables: key lookup (25 rows) | PostgreSQL | 14.7x / 6.1x / 407.6x | same |
|  | MySQL | 4.9x / 107.3x / 1735.1x | 4.3x / 50.4x / 881.7x |
|  | MariaDB | 5.9x / 78.4x / 506.1x | 5.1x / 51.3x / 317.5x |
|  | SQL Server | 2.2x / 375.2x / 232.5x | 5.3x / 371.7x / 143.7x |
|  | Oracle | 1.5x / 5.3x / 17.7x | same |
| full scan: COUNT(*) of 100k rows | PostgreSQL | 2.3x / 3.4x / 4.7x | same |
|  | MySQL | 2.1x / 8.5x / 14.7x | 4.3x / 6.2x / 10.5x |
|  | MariaDB | 3.3x / 5x / 13.8x | 11.7x / 12.7x / 20.4x |
|  | SQL Server | 2.1x / 6.4x / 7.5x | 2.1x / 6.4x / 7.5x |
|  | Oracle | 3.6x / 7.4x / 17.5x | same |

Where the time goes (plans at 10k, `-Dnativejdbc.bench.explain`):

- **Each in-flight copy reads every in-flight row of its table.** Its filter is on `before_*`, which
  no index covers. PostgreSQL reads all 10k in-flight rows through the partial in-flight index and
  drops 9,900 of them on `before_region`. SQL Server scans the whole metadata table (500k rows for
  `o`), because its filtered index holds only the key; that is the 120x on "FK filter" at both 10k
  and 100k. A key lookup stays flat on every engine, because the key bounds the metadata row.
- **The DISTINCT set is built in full on every execution.** MySQL materializes all 10k in-flight
  keys before probing one (5.8 ms for a key lookup), so the driver's MySQL and MariaDB form grows
  with the in-flight count even when the query touches one row. NOT EXISTS stays at 0.9-1.2x for
  key lookups at 100k.
- The committed check itself (NOT EXISTS) stays cheap at any count where the query is selective.

Findings:

- **At 100 rows in flight nothing changes** from the earlier sections.
- **At 10k the selective shapes cost 3-6x on PostgreSQL and Oracle, 5-107x on MySQL and MariaDB,
  and 32-375x on SQL Server** (key lookups aside), all from the in-flight copies, and on MySQL and
  MariaDB also from the DISTINCT set. BASE takes about 0.5 ms; the worst case is a 4-table key
  lookup on SQL Server at 260 ms.
- **At 100k (half of `c`) the split is far from BASE everywhere**: 4-18x on Oracle, 13-37x on
  PostgreSQL (408x for the 4-table key lookup), up to 1,735x for a 4-table key lookup on MySQL.
- **On MySQL and MariaDB neither form wins everywhere.** At 100 rows the DISTINCT set is 2-35x
  better on joins returning many rows and on the IN subquery (the reason it is the driver's form).
  From 10k NOT EXISTS is 1.2-2.4x better on selective shapes and flat on key lookups, but not
  better on joins returning many rows.

What would fix it (not done here):

1. An index on the before image in each metadata table, mirroring each secondary index of the data
   table, restricted to rows in flight where the engine has partial or filtered indexes (PostgreSQL,
   SQL Server), otherwise led by `tx_state`. The in-flight copy could then seek, as the committed
   copy does. On SQL Server, also covering the before columns would end the scan of the metadata
   table. This is setup work, with a write cost on every ScalarDB prepare.
2. On MySQL and MariaDB, choose NOT EXISTS over the DISTINCT set once a table's in-flight count is
   large (from the statistics the setup already gathers, or a cheap count). Below a few hundred rows
   the DISTINCT set stays.
3. Liveness first: the in-flight count stays small only if stuck rows are recovered (todo 084) and
   reported (todo 085).

## Wide tables and composite keys (2026-10-12)

Two tables shaped like real ScalarDB tables, 100k rows each, 100 rows in flight:

- `w`: 49 non-key columns (`c01`, indexed, then `c02` to `c49`).
- `k`: keyed on `(pid, cid)`, 10 rows per partition, with an indexed `grp`.

Shapes: on `w`, a key lookup, an indexed filter, and joins of 2 and 4 copies of `w` on the key, each
reading 1 or 2 columns per table; on `k`, a full-key lookup, a partition scan, and a join on the full
key. New mode and setting:

- `SPLIT_DRVN`: the driver form with each derived table projecting only the key and the columns the
  statement references, instead of every column.
- `-Dnativejdbc.bench.fresh=true`: every execution is a new statement with the values inline, so
  every engine parses and plans it again. This is the cost of a statement no cache holds.

Every mode returned BASE's rows. Medians in ms (ratio to BASE):

| shape | engine | BASE | all columns | referenced only | fresh: BASE | fresh: all | fresh: referenced |
|---|---|---|---|---|---|---|---|
| wide: key lookup, 2 of 50 columns | PostgreSQL | 0.47 | 0.43 (0.9x) | 0.41 (0.9x) | 0.53 | 0.79 (1.5x) | 0.61 (1.2x) |
|  | MySQL | 0.57 | 0.71 (1.3x) | 0.58 (1x) | 0.65 | 0.77 (1.2x) | 0.62 (1x) |
|  | MariaDB | 0.46 | 0.63 (1.4x) | 0.52 (1.1x) | 0.47 | 0.61 (1.3x) | 0.52 (1.1x) |
|  | SQL Server | 0.67 | 0.72 (1.1x) | 0.62 (0.9x) | 1.70 | 5.59 (3.3x) | 4.59 (2.7x) |
|  | Oracle | 0.52 | 0.51 (1x) | 0.46 (0.9x) | 1.25 | 2.92 (2.3x) | 2.27 (1.8x) |
| wide: indexed filter, 1 of 50 columns (100 rows) | PostgreSQL | 0.51 | 0.49 (1x) | 0.48 (0.9x) | 0.53 | 0.91 (1.7x) | 0.73 (1.4x) |
|  | MySQL | 0.70 | 0.96 (1.4x) | 0.96 (1.4x) | 0.70 | 1.00 (1.4x) | 0.88 (1.3x) |
|  | MariaDB | 0.59 | 0.98 (1.7x) | 0.89 (1.5x) | 0.60 | 0.89 (1.5x) | 0.86 (1.4x) |
|  | SQL Server | 0.88 | 1.23 (1.4x) | 1.10 (1.2x) | 2.80 | 9.21 (3.3x) | 8.41 (3x) |
|  | Oracle | 1.73 | 2.23 (1.3x) | 2.12 (1.2x) | 2.37 | 5.29 (2.2x) | 4.55 (1.9x) |
| wide: 2 tables JOIN on key (100 rows) | PostgreSQL | 0.57 | 1.04 (1.8x) | 1.04 (1.8x) | 0.73 | 3.48 (4.8x) | 2.47 (3.4x) |
|  | MySQL | 0.78 | 1.95 (2.5x) | 1.74 (2.2x) | 0.86 | 2.58 (3x) | 2.14 (2.5x) |
|  | MariaDB | 0.69 | 1.78 (2.6x) | 1.46 (2.1x) | 0.75 | 2.34 (3.1x) | 1.56 (2.1x) |
|  | SQL Server | 1.05 | 1.94 (1.9x) | 1.95 (1.9x) | 4.24 | 28.90 (6.8x) | 24.77 (5.8x) |
|  | Oracle | 1.71 | 2.47 (1.4x) | 2.34 (1.4x) | 2.57 | 17.34 (6.7x) | 12.09 (4.7x) |
| wide: 4 tables JOIN on key, key lookup | PostgreSQL | 0.41 | 0.87 (2.1x) | 0.83 (2x) | 0.56 | 21.81 (39.2x) | 15.16 (27.3x) |
|  | MySQL | 0.46 | 5.89 (12.9x) | 2.31 (5.1x) | 0.47 | 7.39 (15.9x) | 2.93 (6.3x) |
|  | MariaDB | 0.44 | 5.52 (12.7x) | 2.44 (5.6x) | 0.45 | 6.30 (14.1x) | 2.52 (5.6x) |
|  | SQL Server | 0.56 | 0.87 (1.5x) | 0.76 (1.4x) | 3.50 | 142.10 (40.6x) | 111.06 (31.8x) |
|  | Oracle | 0.39 | 0.48 (1.2x) | 0.49 (1.2x) | 1.55 | 188.59 (121.9x) | 55.23 (35.7x) |
| composite key: full-key lookup | PostgreSQL | 0.39 | 0.45 (1.2x) | 0.45 (1.2x) | 0.43 | 0.62 (1.4x) | 0.64 (1.5x) |
|  | MySQL | 0.47 | 0.55 (1.2x) | 0.54 (1.2x) | 0.43 | 0.59 (1.4x) | 0.60 (1.4x) |
|  | MariaDB | 0.33 | 0.53 (1.6x) | 0.52 (1.6x) | 0.43 | 0.52 (1.2x) | 0.52 (1.2x) |
|  | SQL Server | 0.60 | 0.57 (1x) | 0.58 (1x) | 1.55 | 5.06 (3.3x) | 5.04 (3.2x) |
|  | Oracle | 0.40 | 0.40 (1x) | 0.39 (1x) | 0.99 | 2.33 (2.4x) | 2.22 (2.2x) |
| composite key: partition scan (10 rows) | PostgreSQL | 0.40 | 0.40 (1x) | 0.39 (1x) | 0.47 | 0.57 (1.2x) | 0.54 (1.2x) |
|  | MySQL | 0.44 | 0.54 (1.2x) | 0.59 (1.4x) | 0.46 | 0.73 (1.6x) | 0.73 (1.6x) |
|  | MariaDB | 0.42 | 0.54 (1.3x) | 0.55 (1.3x) | 0.42 | 0.56 (1.3x) | 0.56 (1.3x) |
|  | SQL Server | 0.55 | 0.64 (1.2x) | 0.66 (1.2x) | 1.60 | 7.69 (4.8x) | 7.74 (4.8x) |
|  | Oracle | 0.81 | 0.91 (1.1x) | 0.86 (1.1x) | 1.40 | 3.42 (2.4x) | 3.36 (2.4x) |
| composite key: JOIN on the full key (100 rows) | PostgreSQL | 0.62 | 0.97 (1.6x) | 0.98 (1.6x) | 0.63 | 2.34 (3.7x) | 2.45 (3.9x) |
|  | MySQL | 0.56 | 1.36 (2.4x) | 1.38 (2.5x) | 0.88 | 1.82 (2.1x) | 2.02 (2.3x) |
|  | MariaDB | 0.57 | 1.53 (2.7x) | 1.53 (2.7x) | 0.61 | 1.54 (2.5x) | 1.40 (2.3x) |
|  | SQL Server | 0.81 | 1.61 (2x) | 1.60 (2x) | 3.74 | 25.34 (6.8x) | 25.19 (6.7x) |
|  | Oracle | 1.82 | 2.55 (1.4x) | 2.49 (1.4x) | 2.57 | 13.72 (5.3x) | 13.81 (5.4x) |

Statement length in characters (the same on every engine):

| shape | BASE | all columns | referenced only |
|---|---|---|---|
| wide: key lookup | 62 | 2,158 | 466 |
| wide: 2 tables JOIN | 100 | 8,525 | 1,685 |
| wide: 4 tables JOIN | 187 | 67,893 | 12,597 |
| composite key: JOIN | 121 | 2,073 | 1,929 |

Findings:

- **Width costs nothing once the plan is cached.** On PostgreSQL, SQL Server and Oracle a
  prepared statement reuses its plan, and the all-columns form is as fast as the narrow one.
- **Width costs every execution on MySQL and MariaDB.** Connector/J and the MariaDB driver prepare
  on the client by default, so the server parses each execution: the 4-table join takes 5.9 ms with
  every column and 2.3 ms with the referenced ones. Fresh and prepared timings are alike there.
- **Width costs every parse elsewhere.** For a fresh 4-table join the all-columns form takes 189 ms
  on Oracle (55 ms narrow), 142 ms on SQL Server (111 ms) and 22 ms on PostgreSQL (15 ms). The
  2^n copies dominate even when narrow: a fresh split is 1.2-7x BASE for 1-2 tables, and 27-36x
  for 4 on PostgreSQL, SQL Server and Oracle.
- **Composite keys cost what single keys do**: 1.0-1.6x for lookups and partition scans, 1.4-2.7x
  for the join, prepared. Key equality over two columns and the DISTINCT set over two columns
  plan as over one.

What follows for the driver:

1. Project only the key and the referenced columns in each fragment (todo 096), keeping every column
   under `*`. It more than halves MySQL and MariaDB on the 4-table wide join, and cuts parse time
   on every engine.
2. Keep plans cached: the driver should prepare on the server where the vendor driver allows it
   (pgjdbc does after 5 executions by default; `useServerPrepStmts` on Connector/J), and avoid
   inlining values, which defeats every plan cache. That stays a configuration note; not measured
   further here.

## Making the split independent of the in-flight count (2026-10-12)

Two parts of the split grew with the in-flight count (section "Many rows in flight"). Each was given
the form that should not:

- **The committed check as a per-row probe.** NOT EXISTS is already one on PostgreSQL, Oracle and
  SQL Server. On MySQL and MariaDB the driver's DISTINCT set reads every in-flight key per
  execution, and MySQL rewrites a plain NOT EXISTS into a materialized anti-join. New mode
  `SPLIT_DAJH` (MySQL): NOT EXISTS with `/*+ NO_SEMIJOIN() */`. `SPLIT_DLJ` (the left join on
  the metadata's primary key) is the per-row form on both.
- **Before-image indexes for the in-flight copy** (`-Dnativejdbc.bench.beforeIndex=true`): each
  secondary index of a data table mirrored on the metadata table's before image, in-flight rows
  only. That is `(before_region) WHERE tx_state IN (1, 2)` on PostgreSQL and SQL Server, and
  `(tx_state, before_region)` on MySQL, MariaDB and Oracle.
- **SQL Server only** (`-Dnativejdbc.bench.ssIndex=include`): the in-flight index with the before
  image as included columns, instead of before-image indexes.

Each run also times a write: a ScalarDB-style prepare (the row in flight, its before image set)
and commit of one row of `cm`, each in its own transaction. All shapes except pagination, at 100
and 10k rows in flight. Every mode returned BASE's rows.

Ratios to BASE, medians. The form is SPLIT_DRV on PostgreSQL, Oracle and SQL Server, and SPLIT_DLJ
(per-row) on MySQL and MariaDB:

| shape | engine | 100: now | 100: before-image | 10k: now | 10k: before-image |
|---|---|---|---|---|---|
| non-key filter (100 rows) | PostgreSQL | 1x | 1x | 2.7x | 1.3x |
|  | Oracle | 1.3x | 1.2x | 3.1x | 1.5x |
|  | SQL Server | 1.6x | 1.3x | 31.2x | 3.3x |
|  | MySQL | 1.4x | 1.3x | 12.4x | 1.3x |
|  | MariaDB | 1.6x | 1.4x | 12.9x | 1.3x |
| FK filter, ORDER BY, LIMIT 3 | PostgreSQL | 1.1x | 1.1x | 3.2x | 1.1x |
|  | Oracle | 1.2x | 1.1x | 5.7x | 1.1x |
|  | SQL Server | 1.3x | 1.2x | 130.6x | 1.2x |
|  | MySQL | 0.9x | 0.8x | 11.8x | 0.7x |
|  | MariaDB | 1.3x | 0.8x | 22.6x | 0.6x |
| key JOIN FK, both ScalarDB | PostgreSQL | 1.1x | 1.3x | 3.2x | 1x |
|  | Oracle | 1.6x | 1.5x | 6.6x | 1.1x |
|  | SQL Server | 2.1x | 1.9x | 29x | 2x |
|  | MySQL | 1.7x | 1.5x | 20.8x | 1.4x |
|  | MariaDB | 1.6x | 1.6x | 20.4x | 1.4x |
| IN subquery on non-key | PostgreSQL | 1.8x | 1.7x | 5.4x | 4.7x |
|  | Oracle | 0.4x | 0.4x | 1.9x | 0.9x |
|  | SQL Server | 2.4x | 1.8x | 171.8x | 3.2x |
|  | MySQL | 2.8x | 1.9x | 28.6x | 8.6x |
|  | MariaDB | 2.2x | 2.8x | 61.9x | 25.9x |
| 3 tables: key lookup (5 rows) | PostgreSQL | 1.3x | 1.3x | 3.2x | 1.3x |
|  | Oracle | 1.7x | 1.5x | 6.8x | 1.4x |
|  | SQL Server | 1.7x | 1.4x | 140.2x | 1.9x |
|  | MySQL | 2.5x | 2.3x | 20.7x | 2.2x |
|  | MariaDB | 2.6x | 2.5x | 20.2x | 2.5x |
| 4 tables: key lookup (25 rows) | PostgreSQL | 17x | 2.1x | 6.7x | 1.8x |
|  | Oracle | 1.5x | 1.4x | 6x | 1.5x |
|  | SQL Server | 2x | 2x | 364.9x | 2.2x |
|  | MySQL | 3.9x | 3.2x | 57.8x | 3.9x |
|  | MariaDB | 5.6x | 4.7x | 54.1x | 5.1x |
| many rows: JOIN FK, 300 customers (1.5k rows) | PostgreSQL | 1.5x | 2.4x | 6.7x | 3.2x |
|  | Oracle | 1.1x | 1.1x | 2x | 1.4x |
|  | SQL Server | 1.8x | 1.5x | 87x | 2.3x |
|  | MySQL | 1.9x | 1.6x | 17.4x | 7.6x |
|  | MariaDB | 2x | 2.5x | 33.2x | 16.8x |
| composite key: JOIN on the full key (100 rows) | PostgreSQL | 1.6x | 1.4x | 6.3x | 1.5x |
|  | Oracle | 1.3x | 1.2x | 3.9x | 1.8x |
|  | SQL Server | 1.7x | 1.3x | 38.8x | 1.3x |
|  | MySQL | 1.8x | 1.7x | 19.1x | 1.6x |
|  | MariaDB | 2.5x | 2.6x | 39x | 2.3x |
| full scan: COUNT(*) of 100k rows | PostgreSQL | 2.3x | 2.3x | 3.4x | 3.7x |
|  | Oracle | 3.6x | 3.4x | 7.2x | 6.8x |
|  | SQL Server | 2.1x | 2.1x | 6.6x | 6.4x |
|  | MySQL | 10.6x | 10.6x | 11.4x | 12.1x |
|  | MariaDB | 9.6x | 9.5x | 10.9x | 10.9x |

Findings:

- **With both changes, the selective shapes at 10k come back to their 100-row ratios on every
  engine**: 0.6-3.3x for indexed filters, joins on keys and foreign keys, 3-table key lookups and
  composite keys; 1.5-5.1x for 4-table key lookups, as at 100. They were 2.7-365x. At 100 rows
  both changes are neutral or slightly faster.
- **Writes show no cost from the extra index.** A prepare plus commit of one row took the same,
  within 8%, with and without before-image indexes on every engine (PostgreSQL 0.87 / 0.87 ms, MySQL
  2.38 / 2.57, MariaDB 0.98 / 1.03, SQL Server 2.51 / 2.40, Oracle 1.15 / 1.22). The commit's log
  flush dominates; a write-heavy workload with many indexed columns may still see it.
- **On SQL Server, including the before image in the in-flight index gets most of the gain**
  without new indexes: at 10k, 31x to 4.7x on the non-key filter and 131x to 2.6x on the FK
  filter. Each in-flight copy still reads every in-flight row, but no longer the whole metadata
  table.
- **The per-row probe costs more where the query reads many rows.** On MySQL and MariaDB at 100
  rows in flight, the left join is 10x BASE on a 100k-row count, against 2.1-3.3x for the DISTINCT
  set. The 15k-row join is 3.4x against 1.3x, and DISTINCT over 100k rows 38x against 11x. Those
  shapes probe the metadata once per row read; the set is built once.
- **Still open at 10k**: the IN subquery (MySQL 8.6x, MariaDB 26x, PostgreSQL 4.7x), joins
  returning many rows on MySQL and MariaDB (7.6x and 17x), and full scans (3.7-12x). These read
  many in-flight rows by nature, or the in-flight copy is joined on a non-key column. MariaDB's
  4-table join over one region got worse with the indexes (55x to 199x), a plan choice not
  investigated.

The next section replaces the conclusions first drawn here.

## ScalarDB's own before-image indexes, and where the per-row check pays (2026-10-12)

ScalarDB already creates a `before_x` index for every column `x` with a ScalarDB secondary index,
unless index-eventually-consistent reads are enabled (`ConsensusCommitUtils
.buildTransactionTableMetadata`, `ConsensusCommitAdmin.createIndex`, v3.19.1). Its commit and
rollback set every `before_*` column to NULL, so only in-flight rows carry a value. Its index is
plain, `(before_x)`, with no state filter. The benchmark's `-Dnativejdbc.bench.beforeIndex=plain`
builds that shape, and its write now clears the before image at commit as ScalarDB does.

**ScalarDB's index serves the in-flight copy like the filtered one.** At 10k rows in flight, driver
form (per-row on MySQL and MariaDB), ratio to BASE without / with the filtered index / with ScalarDB's
plain index:

| shape | PostgreSQL | Oracle | SQL Server | MySQL | MariaDB |
|---|---|---|---|---|---|
| non-key filter | 2.7 / 1.3 / 1.3 | 3.1 / 1.5 / 1.5 | 31 / 3.3 / 3.1 | 12 / 1.3 / 1.5 | 13 / 1.3 / 1.4 |
| FK filter, LIMIT 3 | 3.2 / 1.1 / 1.0 | 5.7 / 1.1 / 1.1 | 131 / 1.2 / 0.9 | 12 / 0.7 / 0.7 | 23 / 0.6 / 0.8 |
| key JOIN FK | 3.2 / 1.0 / 1.2 | 6.6 / 1.1 / 1.2 | 29 / 2.0 / 1.5 | 21 / 1.4 / 1.5 | 20 / 1.4 / 1.5 |
| 4 tables, key lookup | 6.7 / 1.8 / 1.8 | 6.0 / 1.5 / 1.6 | 365 / 2.2 / 1.5 | 58 / 3.9 / 3.2 | 54 / 5.1 / 4.9 |
| 3 tables, one region | 20 / 5.7 / 27 | 2.0 / 1.0 / 0.6 | 158 / 3.5 / 3.8 | 26 / 11 / 6.9 | 46 / 17 / 4.1 |
| JOIN FK, 300 customers | 6.7 / 3.2 / 3.1 | 2.0 / 1.4 / 1.3 | 87 / 2.3 / 31 | 17 / 7.6 / 5.8 | 33 / 17 / 11 |
| composite key JOIN | 6.3 / 1.5 / 3.2 | 3.9 / 1.8 / 1.8 | 39 / 1.3 / 2.1 | 19 / 1.6 / 1.7 | 39 / 2.3 / 2.2 |

Writes (prepare and commit of one row) took the same with either index: PostgreSQL 0.86 ms, MySQL
2.50, MariaDB 0.93, SQL Server 2.61, Oracle 1.20. Every mode returned BASE's rows.

The plain index matches the filtered one on single-table and key shapes. On some joins over many
rows PostgreSQL and SQL Server chose worse plans with it (PostgreSQL 3 tables over one region 27x
against 5.7x; SQL Server's join of 300 customers 31x against 2.3x). On MySQL and MariaDB it did
better than the filtered one there. Not investigated; part of todo 094 (plan stability).

**On MySQL and MariaDB the per-row check pays from about 1,000 rows in flight.** DISTINCT set / per-row
(left join), ratio to BASE, ScalarDB's plain index present:

| shape | engine | 100 | 1k | 3k | 10k |
|---|---|---|---|---|---|
| key lookup | MySQL | 1.0 / 0.8 | 1.6 / 0.8 | 3.6 / 0.8 | 7.9 / 0.8 |
| | MariaDB | 1.0 / 1.0 | 1.3 / 1.0 | 2.0 / 1.0 | 4.2 / 1.0 |
| key JOIN FK | MySQL | 1.6 / 1.3 | 3.3 / 1.4 | 6.9 / 1.3 | 23 / 1.5 |
| | MariaDB | 1.6 / 1.4 | 2.4 / 1.7 | 4.4 / 1.7 | 9.4 / 1.5 |
| 4 tables, key lookup | MySQL | 4.3 / 3.5 | 5.9 / 3.2 | 10.5 / 3.3 | 26 / 3.2 |
| | MariaDB | 5.5 / 4.7 | 5.9 / 4.6 | 8.2 / 4.6 | 17 / 4.9 |
| JOIN FK, 3k customers | MySQL | 1.4 / 3.8 | 3.1 / 4.2 | 4.3 / 4.5 | 7.8 / 6.2 |
| | MariaDB | 2.1 / 4.2 | 2.7 / 4.6 | 3.4 / 4.9 | 6.4 / 7.0 |
| full scan: COUNT(*) | MySQL | 2.0 / 10.5 | 5.4 / 10.9 | 6.4 / 11.3 | 8.5 / 12.3 |
| | MariaDB | 3.3 / 9.6 | 3.5 / 9.7 | 4.1 / 10.2 | 5.1 / 11.0 |

- Where a table is reached through its key or an indexed column, the per-row check is as fast as the
  set at 100 rows, and faster from 1k on; it stays flat while the set grows with the count.
- Where a table is scanned (full scans, DISTINCT over a whole table), the set wins at every count
  measured, 1.4-5x: the per-row check probes the metadata once per row read. Joins reading
  thousands of rows lean the same way up to 3k and turn at 10k.

What follows for the driver:

1. **No before-image indexes from the driver.** ScalarDB's own serve the in-flight copy. The setup
   reports what they do not cover: a data-table index with no `before_*` counterpart (a
   multi-column index, or one created outside ScalarDB, as on imported tables), and a table whose
   before-image indexes are missing because index-eventually-consistent reads are on. It prints the
   DDL to add each one, and creates it only on an explicit option (todo 102).
2. **On MySQL and MariaDB, choose the committed check per table in each copy** (todo 103): the
   per-row check where the statement reaches the table through its key or an indexed column and
   the table has more than a few hundred rows in flight; the DISTINCT set otherwise. The count is
   read through the in-flight index and cached briefly.
3. Primary-key access needs nothing on any engine.

## Through the driver (2026-10-13)

New mode `DRIVER`: BASE's statement run through the driver. The benchmark's tables are imported
into ScalarDB, decoupled. ScalarDB indexes are created on the indexed columns, through each table's
virtual table, so that ScalarDB also indexes the before image (todo 102). The benchmark's metadata
rows are copied into ScalarDB's metadata tables, statistics are gathered, and the driver's setup
is run. The driver connection keeps the default catalog TTL; the tests' 0 reloads the catalog on
every execution, which first measured as 4-5 ms per statement. Run with
`-Dnativejdbc.bench.modes=SPLIT_DRV,DRIVER -Dnativejdbc.bench.beforeIndex=plain`, at 100 and at
10,000 rows in flight. `SPLIT_DRV` is the hand-written form of the previous sections. Every mode
returned BASE's rows.

The driver now adds what the earlier sections only measured:
- per-copy aggregates;
- referenced columns only (todo 096);
- the per-table committed check on MySQL and MariaDB (todo 103);
- the limit in each copy (todo 089);
- PostgreSQL split reads without parallel workers unless the plain plan is parallel.

Ratios to BASE, medians, `SPLIT_DRV / DRIVER`:

100 rows in flight:

| shape | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| key lookup | 0.8 / 1 | 1.1 / 1.1 | 1 / 1.1 | 1.1 / 1.2 | 1 / 1.1 |
| non-key filter (100 rows) | 1.3 / 0.9 | 1.2 / 1.2 | 1.3 / 1.4 | 1.3 / 1.2 | 1.2 / 1.4 |
| FK filter, ORDER BY, LIMIT 3 | 0.9 / 1 | 0.8 / 0.7 | 0.7 / 0.7 | 1 / 0.9 | 1.3 / 1.3 |
| key JOIN FK, both ScalarDB | 1.1 / 1.2 | 1.6 / 1.4 | 1.4 / 1.6 | 1.6 / 1.5 | 1.7 / 1.7 |
| IN subquery on non-key | 1.3 / 1.9 | 1.3 / 1.3 | 2.2 / 3.2 | 2.2 / 2.7 | 0.4 / 1.6 |
| LEFT JOIN, ScalarDB on both sides | 1.2 / 1.3 | 2.1 / 1846.7 | 2.1 / 788.3 | 2 / 1.1 | 1.6 / 1.3 |
| full scan: SUM of 500k rows | 1.3 / 2.7 | 2 / 1.6 | 3.1 / 2.2 | 1.2 / 1.2 | 2.9 / 1.6 |
| 3 tables: key lookup (5 rows) | 1.3 / 1.4 | 2.5 / 2.4 | 2.4 / 2.6 | 1.6 / 1.8 | 1.6 / 1.5 |
| 4 tables: key lookup (25 rows) | 2 / 2.1 | 4.1 / 4 | 5.2 / 4.8 | 1.9 / 1.6 | 1.6 / 1.6 |
| page: ORDER BY key DESC, 20 rows of 500k | 45.6 / 1.1 | 233.4 / 1.7 | 368 / 1.9 | 1.6 / 1.2 | 34.5 / 1.1 |
| page: JOIN FK, ORDER BY key DESC, 20 rows | 100.1 / 2.4 | 348.4 / 6.2 | 1.8 / 1.5 | 3.9 / 3.3 | 95 / 2.1 |
| wide: 4 tables JOIN on key, key lookup | 2.1 / 2 | 13.7 / 5.4 | 14.1 / 5.5 | 1.6 / 1.3 | 1.3 / 1.3 |

10k rows in flight:

| shape | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| key lookup | 1.1 / 1 | 9.2 / 0.9 | 4.6 / 1.1 | 1 / 1 | 1.1 / 1 |
| non-key filter (100 rows) | 1.4 / 1.4 | 10.8 / 1.6 | 4.7 / 1.3 | 3.2 / 3.9 | 1.5 / 1.3 |
| FK filter, ORDER BY, LIMIT 3 | 1 / 1.1 | 6.3 / 0.7 | 3.2 / 0.8 | 1.1 / 1.1 | 1.2 / 1.1 |
| key JOIN FK, both ScalarDB | 1.1 / 1.1 | 21.3 / 1.6 | 10.1 / 1.7 | 1.6 / 1.7 | 1.3 / 1.1 |
| IN subquery on non-key | 5.7 / 3.1 | 15.4 / 10.1 | 9 / 6.1 | 3.3 / 4 | 0.6 / 2 |
| LEFT JOIN, ScalarDB on both sides | 1.1 / 1.2 | 31.5 / 1795.8 | 14.3 / 996.3 | 1.8 / 1.1 | 1.2 / 1 |
| full scan: SUM of 500k rows | 5.9 / 3.9 | 4.5 / 4 | 3.7 / 2.7 | 0.5 / 0.5 | 4.1 / 3.1 |
| 3 tables: key lookup (5 rows) | 1.3 / 1.3 | 23.5 / 2.8 | 10.1 / 2.6 | 1.2 / 1.4 | 1.4 / 1.3 |
| 4 tables: key lookup (25 rows) | 2 / 1.9 | 30.9 / 4.4 | 14.7 / 4.4 | 2.2 / 1.8 | 1.7 / 1.7 |
| page: ORDER BY key DESC, 20 rows of 500k | 70.5 / 1.2 | 406.2 / 35.1 | 415.7 / 5.9 | 1.2 / 1.7 | 44.5 / 1.1 |
| page: JOIN FK, ORDER BY key DESC, 20 rows | 155.1 / 168.3 | 1065.6 / 229.4 | 2.4 / 5.7 | 261.4 / 333.8 | 144.1 / 46.1 |
| wide: 4 tables JOIN on key, key lookup | 2.1 / 1.9 | 53.4 / 3.8 | 25.9 / 4.6 | 1.8 / 1.5 | 1.3 / 1.3 |

- **Selective shapes match the hand-written form at 100 rows in flight** on every engine, within
  about 20%, except the IN subquery, which the driver reads through `rc_T` (3c): 1.6x against 0.4x
  on Oracle, 3.2x against 2.2x on MariaDB, 2.7x against 2.2x on SQL Server.
- **At 10,000 rows in flight, MySQL and MariaDB are far better through the driver.** The per-row
  check on tables reached through an index takes key lookups from 9.2x to 0.9x, key joins from 21x
  to 1.6x and four tables from 31x to 4.4x.
- **Pagination is close to the plain query** on PostgreSQL, SQL Server and Oracle at both counts,
  and on MariaDB at 100 rows in flight; MariaDB is 5.9x at 10,000. MySQL is 1.7x at 100 rows in
  flight and 35x at 10,000.
- **Still open:**
  - a ScalarDB table on the nullable side of a LEFT JOIN, 790-1800x on MySQL and MariaDB,
    which milestone 3c splits;
  - the IN subquery on every engine (PostgreSQL 1.9-3.1x; see above for the others), also 3c;
  - a paginated join at 10,000 rows in flight, 46-334x on every engine but MariaDB, where the
    hand-written form is as bad;
  - PostgreSQL's SUM of 500k rows at 100 rows in flight, 2.7x against 1.3x.
- **Wide tables gain from reading only the columns used**: four wide tables are 5.4x against 13.7x
  on MySQL.

**What routing costs**, `prepareStatement` through the vendor driver against through the driver,
median µs, before the route cache. None of the vendor drivers contacts the server at prepare, so
the difference is the driver's parse and rewrite. Not quite nothing was cached: the driver's own
server calls, PostgreSQL's plan check and the MySQL/MariaDB in-flight count, were cached for most
of the 1,300 repetitions, so a statement seen for the first time also pays a round trip that the
table does not show (todo 098):

| shape | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| key lookup | 1 / 432 | 11 / 448 | 1 / 338 | 4 / 329 | 2 / 327 |
| key JOIN FK, two tables | 0 / 489 | 8 / 619 | 0 / 526 | 0 / 432 | 2 / 471 |
| 3 tables, key lookup | 0 / 837 | 11 / 1213 | 0 / 987 | 0 / 820 | 2 / 900 |
| 4 tables, key lookup | 0 / 1711 | 14 / 2582 | 1 / 1986 | 0 / 1588 | 3 / 1810 |

Todo 008's target is under 150 µs for an uncached statement and under 10 µs for a cached one. A
statement run once, as `Statement.execute` or an ORM that does not keep its prepared statements,
pays 0.3-2.6 ms: as much as the query itself for a key lookup. The statement cache is needed.

**With the route cache (todo 008, 2026-10-14)**: each connection keeps the routes of its last 256
texts, the parser runs on shared threads, and a statement without comments is not lexed again after
its split is emitted. `prepareStatement`, median µs, vendor / through the driver, same text / new
text each time (leading blanks, which the cache does not hold):

| shape | MySQL | SQL Server |
|---|---|---|
| key lookup | 14 / 30 / 299 | 2 / 4 / 308 |
| key JOIN FK, two tables | 8 / 72 / 410 | 1 / 2 / 338 |
| 3 tables, key lookup | 11 / 211 / 646 | 0 / 3 / 418 |
| 4 tables, key lookup | 14 / 543 / 1111 | 0 / 6 / 543 |

A repeated text costs the driver 1-5 µs. What remains on MySQL, and on MariaDB and Oracle (up to
50 and 150 µs for four tables), is the vendor driver's own prepare of the longer split text, which
it scans for placeholders. A new text costs two parses, the statement and its first copy, about
0.3-0.5 ms on SQL Server, above todo 008's 150 µs; MySQL adds the vendor's prepare of the split
text. On PostgreSQL a new text also asks the server for the plain statement's plan (design 4.2),
about 1-5 ms once per text and connection.

**Parsed once (milestone 6, 2026-10-15)**: the split's copies are no longer parsed again outside
the tests. The leading blanks above inflated the parse, so it is measured again with
`bench/RouteCostBenchmark` on four small tables, a new text differing by an alias. A key lookup
miss is 84 µs on SQL Server and 104 µs on MySQL; the full table is in docs/plans/proxy-step.md,
milestone 6.

## LEFT JOINs and subqueries through the driver (milestone 3c, 2026-10-14)

The driver now splits a ScalarDB table on the nullable side of a LEFT JOIN, in an IN or EXISTS
subquery that is a top-level conjunct of the WHERE clause, and in IN, EXISTS, NOT IN and NOT
EXISTS elsewhere in the WHERE clause (design 4.2, "Which tables are split"). Run as the previous
section, `-Dnativejdbc.bench.only='IN subquery|LEFT JOIN|NOT EXISTS'`, at 100 and 10,000 rows in
flight. Two shapes are new:
- "IN subquery selecting a non-key", `a.id IN (SELECT b.customer_id FROM o b)`: the in-flight piece
  adds that the subquery over the committed piece does not match, as a correlated `NOT EXISTS`.
- "NOT EXISTS, ScalarDB on both sides": the subquery is written over both pieces inside every
  copy.

"IN subquery on non-key" selects the subquery table's key, so it is split as an inner table. Every
mode returned BASE's rows. `-Dnativejdbc.bench.explain` now shows the driver's SQL for `DRIVER`,
which `Connection.nativeSQL` returns.

Ratios to BASE, medians, through `rc_T` before 3c / through the driver now. "Through `rc_T`" is the
previous section's `DRIVER` for the LEFT JOIN and the key IN, and this run's `SPLIT_DRV`, the outer
table split and the subquery's through its view, for the new shapes:

100 rows in flight:

| shape | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| LEFT JOIN, ScalarDB on both sides | 1.3 / 1.2 | 1847 / 2.3 | 788 / 2.2 | 1.1 / 2.1 | 1.3 / 1.7 |
| IN subquery selecting the key | 1.9 / 1.4 | 1.3 / 1.7 | 3.2 / 2.4 | 2.7 / 2.1 | 1.6 / 0.4 |
| IN subquery selecting a non-key | 192 / 1.7 | 300 / 2.5 | 1524 / 2.2 | 3.5 / 5.0 | 2.8 / 5.4 |
| NOT EXISTS, ScalarDB on both sides | 93 / 1.1 | 307 / 13.6 | 399 / 1.7 | 3.0 / 3.6 | 6.0 / 6.3 |

10,000 rows in flight:

| shape | PostgreSQL | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| LEFT JOIN, ScalarDB on both sides | 1.2 / 1.2 | 1796 / 1.8 | 996 / 1.9 | 1.1 / 2.2 | 1.0 / 1.3 |
| IN subquery selecting the key | 3.1 / 5.1 | 10.1 / 16.3 | 6.1 / 7.4 | 4.0 / 3.8 | 2.0 / 0.5 |
| IN subquery selecting a non-key | 389 / 3.7 | 283 / 26.7 | 1422 / 6.8 | 2.8 / 3.8 | 1.7 / 2.0 |
| NOT EXISTS, ScalarDB on both sides | 125 / 2.1 | 356 / 2.0 | 371 / 1.6 | 1.9 / 1.7 | 3.1 / 2.8 |

After todo 121 the key IN is split as an inner table on PostgreSQL only; on the other engines a
text key compared with a number could match in both pieces, so it takes the correlated `NOT EXISTS`
too. Through the driver, before / after (2026-10-14, `-Dnativejdbc.bench.only='IN subquery on
non-key'`):

| rows in flight | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|
| 100 | 1.7 / 1.8 | 2.4 / 2.5 | 2.1 / 2.7 | 0.4 / 0.4 |
| 10,000 | 16.3 / 13.8 | 7.4 / 7.3 | 3.8 / 4.8 | 0.5 / 0.5 |

The forms these numbers led to:
- **A nullable table and a subquery's table take the per-row committed check on MySQL and
  MariaDB** when a top-level conjunct of an ON clause or of the subquery's WHERE clause reaches them
  through an index, as inner tables do. Without it the LEFT JOIN measured 22.5x on MySQL and 8.6x on
  MariaDB at 10,000 rows in flight.
- **A semi-joined IN over a non-key is checked against the committed piece by a correlated `NOT
  EXISTS`** on the subquery's index. The first form, `CASE WHEN x IN (S_c) THEN 1 ELSE 0 END = 0`,
  made PostgreSQL hash the whole committed subquery, 500k rows: 1205x at 10,000 rows in flight. The
  CASE form remains where the correlated one could read an outer column as the subquery's.
- **NOT EXISTS is written `NOT EXISTS (S_c) AND NOT EXISTS (S_f)`**, not as NOT over an OR of the
  two EXISTS, which MySQL ran row by row at 152-175x. MySQL still built the in-flight piece's
  anti-join whole, by a scan of the metadata table, whose state it estimates at every row, so on
  MySQL the in-flight piece of a subquery in an expression carries `/*+ NO_SEMIJOIN() */` and is
  probed per outer row: 156x down to 13.6x at 100 rows in flight, 177x down to 2.0x at 10,000. At
  100 it still reads every row in flight per probe, through the in-flight index rather than the
  before image's (todo 118). MariaDB reads the hint as a comment. The MySQL `NOT EXISTS` figures are
  from a separate run with the hint; `MYSQL_LOWER_CASE` was not run again.

What is left:
- **MySQL at 10,000 rows in flight**: the IN subqueries, 16-27x; the hand-written form is as slow
  (16x). Not explained yet; likely the outer table, which is reached only through the IN, which the
  per-row check does not follow, so it builds the DISTINCT set of 10,000 keys.
- **SQL Server and Oracle read some subqueries better through the view**: an IN over a non-key at
  100 rows in flight, 5.0x and 5.4x against 3.5x and 2.8x; SQL Server's LEFT JOIN 2.1-2.2x against
  1.1x. Both still stay within a few times the plain query.
- **PostgreSQL's key IN at 10,000 rows in flight**, 5.1x against 3.1x through the view.
