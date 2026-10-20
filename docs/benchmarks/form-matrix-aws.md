# Form matrix on AWS, 2026-10-21

The first run of [the benchmark environment](../plans/benchmark-environment.md): all 33 read forms of
`ReadOverheadBenchmark`, one repetition, 100 and 10,000 rows in flight, a 30-second query timeout.

- Tier A (EC2 m6id.2xlarge, Docker on the instance store, tuned settings): PostgreSQL 16.15, MySQL 8.4,
  MariaDB 11.4.
- Tier B (RDS db.m5.2xlarge, default parameter groups): PostgreSQL 16.13, Oracle SE2 19, MySQL 8.4,
  MariaDB 11.4. Oracle on tier A waits for an Enterprise Edition image.
- Client: m6i.4xlarge in the same subnet. About 2.2 hours from apply to destroy, about $13.

Ratios are to BASE, the same query on plain tables. `bench-env/rank.py` picks, per query, the fastest
form whose rows equal BASE's; the flag is DRIVER's time over the best form's, shown where above 1.5x.
A ratio of 0.0x is a form faster than BASE by three orders of magnitude, which happens where the
plain-table plan is itself poor (MariaDB's paged join: BASE 204 ms, VIEW_CASE 0.4 ms).

## Findings

1. **Paging is where DRIVER loses most, on every engine.** VIEW_CASE wins the `ORDER BY key ... 20 rows`
   queries almost everywhere, and DRIVER trails it by 2x to 2,000x: MySQL's 3-table paged join takes
   about 1 s under DRIVER against 0.5 ms under VIEW_CASE on both tiers; Oracle's keyset page is 37x,
   its paged JOIN FK 163x at 10,000 in flight; PostgreSQL's keyset page 14x on RDS. This is the case
   [the paging route plan](../plans/paging-route.md) addresses, now measured on managed databases.
2. **VIEW_CASE is not a general answer.** On MariaDB it is 427x to 2,861x on non-key filters, FK
   filters with LIMIT and COUNT by FK: the route has to stay specific to paging.
3. **PostgreSQL aggregates over full scans** prefer the split forms (SPLIT_AJ, SPLIT_MX, SPLIT_MH):
   SUM of 500k rows is 8.0x behind on tier A and 2.5x on tier B.
4. **Wide 4-table key lookups** prefer VIEW_CASE (PostgreSQL 3.2x to 3.5x, MySQL and MariaDB 2.4x
   to 3.1x).
5. **No form helps** `DISTINCT over an indexed column` on MySQL and MariaDB (17x to 54x), nor
   PostgreSQL's paged joins at 10,000 in flight (64x to 621x for the best form): both need a
   different plan rather than a different form.
6. Tier A and tier B mostly agree: largely the same queries flag, by different factors. The RDS defaults
   (`work_mem` 4 MB, `random_page_cost` 4 on PostgreSQL) widen several gaps.

One repetition only: single-digit factors are indicative; the large ones are not noise.

## Recommendation

Which form to read each kind of query with, per engine. Keep means the split read the driver emits
today (SPLIT_DRV, `SplitRead.java`): the committed copy excludes rows in flight by `NOT EXISTS` on
PostgreSQL and Oracle, and by a left join to the DISTINCT in-flight keys on MySQL and MariaDB.
Brackets give the gain over DRIVER measured here.

| Query type | PostgreSQL | Oracle | MySQL | MariaDB |
|---|---|---|---|---|
| Key lookups, filters, FK filters, IN / NOT EXISTS subqueries, LEFT JOIN, 2-4 table joins | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, DISTINCT set) | Keep (SPLIT_DRV, DISTINCT set) |
| Paging: ORDER BY key + LIMIT, keyset, indexed range + LIMIT | VIEW_CASE (1.7x-14x) | VIEW_CASE (10x-37x) | VIEW_CASE (2x-111x) | VIEW_CASE (1.5x-11x) |
| Paged join, 2-3 tables | SPLIT_DRVP (3.6x-7.4x); VIEW_CASE with the plain filter to confirm | VIEW_CASE (4x-163x) | VIEW_CASE (8x-2,800x) | VIEW_CASE (530x-1,200x) |
| Deep OFFSET page | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, NOT EXISTS) | VIEW_CASE (3.5x-13x) | VIEW_CASE (3.7x-4.5x) |
| Full-scan aggregates | SPLIT_DRVN (1.6x-8x) | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, DISTINCT set) | Keep (SPLIT_DRV, DISTINCT set) |
| DISTINCT | SPLIT_NI or SPLIT_DHDP (1.5x-2.3x) | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, DISTINCT set); no form helps | Keep (SPLIT_DRV, DISTINCT set); no form helps |
| Wide tables, 4-table key lookup | VIEW_CASE (3.2x-3.5x) | Keep (SPLIT_DRV, NOT EXISTS) | VIEW_CASE (1.7x-2.7x) | VIEW_LJ (2.3x-3.1x) |

This run's VIEW_CASE used the `COALESCE` filter, not the plain one the driver emits; on PostgreSQL's
paged joins that form is the planner's misestimate, so those cells need a rerun with
`-Dnativejdbc.bench.plainCaseFilter=true`. Forms that beat DRIVER by up to 1.5x on joins at 100 rows
in flight lose to it at 10,000, and the driver cannot know the count when it plans, so those stay Keep.

## Rankings

### Tier A (EC2)


#### MARIADB, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DLJ | 1.3x | 1.7x |  |
| non-key filter (100 rows) | DRIVER | 1.6x | 1.6x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.9x | 1.9x |  |
| COUNT by FK | DRIVER | 1.9x | 1.9x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.4x | 2.0x |  |
| key JOIN FK, both ScalarDB | SPLIT_LJ | 2.4x | 2.7x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_HD | 2.0x | 2.3x |  |
| IN subquery on non-key | DRIVER | 3.3x | 3.3x |  |
| IN subquery selecting a non-key | DRIVER | 3.5x | 3.5x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 1.9x | 1.9x |  |
| LEFT JOIN, ScalarDB on both sides | SPLIT_LJ | 3.4x | 4.8x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPH | 2.1x | 2.1x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 2.6x | 2.6x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 2.0x | 2.1x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 2.1x | 2.1x |  |
| full scan: unindexed filter | SPLIT_LJ | 1.0x | 1.1x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 1.7x | 1.7x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_HD | 1.6x | 1.9x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_DRVN | 1.4x | 1.5x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LJ | 3.5x | 5.1x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 2.4x | 2.7x |  |
| 4 tables: key lookup (25 rows) | VIEW_AJ | 5.7x | 11.1x | **1.9x** |
| 4 tables: one region (2.5k rows) | SPLIT_LH | 2.3x | 3.3x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.6x | 3.3x | **2.1x** |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DRVP | 3.9x | 4.4x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 1.4x | **699.4x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.5x | 2.3x | **1.5x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 1.4x | **589.4x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.4x | 4.7x | **3.3x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DRVP | 26.5x | 33.3x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.3x | 1.7x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.5x | 1.5x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.5x | 2.5x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_LJ | 4.2x | 13.2x | **3.1x** |
| composite key: full-key lookup | SPLIT_LJ | 1.3x | 1.8x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.5x | 2.0x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.5x | 3.5x |  |
| hot: key lookup | SPLIT_LJ | 1.3x | 1.7x |  |
| hot: indexed filter (100 rows) | DRIVER | 1.7x | 1.7x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.6x | 3.1x | **1.9x** |
| hot: COUNT(*) of 20k rows | SPLIT_PH | 2.2x | 2.3x |  |

#### MARIADB, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_LJ | 1.2x | 1.4x |  |
| non-key filter (100 rows) | DRIVER | 2.3x | 2.3x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.8x | 1.8x |  |
| COUNT by FK | DRIVER | 1.6x | 1.6x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.4x | 1.6x |  |
| key JOIN FK, both ScalarDB | DRIVER | 2.2x | 2.2x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 8.7x | 8.7x |  |
| IN subquery on non-key | DRIVER | 12.0x | 12.0x |  |
| IN subquery selecting a non-key | DRIVER | 15.7x | 15.7x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 2.1x | 2.1x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 4.0x | 4.0x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPH | 4.2x | 4.4x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 8.1x | 8.1x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 2.6x | 2.6x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 4.2x | 4.6x |  |
| full scan: unindexed filter | SPLIT_LJ | 1.8x | 2.2x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 23.7x | 23.7x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 7.6x | 7.6x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_HD | 2.3x | 4.5x | **1.9x** |
| 3 tables: key lookup (5 rows) | DRIVER | 4.7x | 4.7x |  |
| 3 tables: one region (500 rows) | DRIVER | 9.0x | 9.0x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 9.3x | 9.3x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 9.8x | 9.8x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.5x | 16.4x | **10.6x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.2x | 32.2x | **4.5x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 2.4x | **1200.7x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.5x | 3.6x | **2.4x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 2.9x | **1226.3x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.3x | 3.3x | **2.5x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DHDP | 45.6x | 54.2x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.3x | 1.4x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.9x | 1.9x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 3.2x | 3.2x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_LJ | 4.3x | 10.2x | **2.4x** |
| composite key: full-key lookup | SPLIT_LJ | 1.3x | 1.5x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.5x | 1.7x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.8x | 3.8x |  |
| hot: key lookup | SPLIT_LJ | 1.3x | 1.5x |  |
| hot: indexed filter (100 rows) | DRIVER | 3.0x | 3.0x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.6x | 2.3x |  |
| hot: COUNT(*) of 20k rows | SPLIT_PH | 9.0x | 10.1x |  |

#### MYSQL, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DLJ | 0.9x | 1.2x |  |
| non-key filter (100 rows) | DRIVER | 1.4x | 1.4x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.3x | 1.3x |  |
| COUNT by FK | DRIVER | 1.8x | 1.8x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.2x | 1.8x |  |
| key JOIN FK, both ScalarDB | SPLIT_LJ | 2.5x | 2.7x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_DHD | 1.8x | 1.9x |  |
| IN subquery on non-key | DRIVER | 2.6x | 2.6x |  |
| IN subquery selecting a non-key | DRIVER | 3.8x | 3.8x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 31.0x | 31.0x |  |
| LEFT JOIN, ScalarDB on both sides | SPLIT_LJ | 3.1x | 4.2x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPH | 1.7x | 1.7x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 2.4x | 2.4x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 1.6x | 1.6x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 2.4x | 2.8x |  |
| full scan: unindexed filter | SPLIT_DHD | 1.0x | 1.0x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 1.7x | 1.7x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_PH | 1.5x | 1.7x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_MH | 1.2x | 1.3x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LJ | 3.4x | 4.6x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 2.8x | 2.9x |  |
| 4 tables: key lookup (25 rows) | SPLIT_LT | 5.5x | 9.5x | **1.7x** |
| 4 tables: one region (2.5k rows) | SPLIT_LH | 2.1x | 2.9x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.4x | 3.0x | **2.2x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.3x | 95.2x | **13.0x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.6x | 13.3x | **8.2x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 1.5x |  |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.7x | 3590.3x | **2071.3x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.3x | 3.0x | **2.3x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DRVP | 16.8x | 19.4x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.1x | 1.5x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.4x | 1.4x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.4x | 2.4x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 4.3x | 10.3x | **2.4x** |
| composite key: full-key lookup | VIEW_CASE | 1.3x | 1.9x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.4x | 1.9x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 2.8x | 2.8x |  |
| hot: key lookup | SPLIT_LJ | 1.2x | 1.8x |  |
| hot: indexed filter (100 rows) | DRIVER | 1.7x | 1.7x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.4x | 2.7x | **1.9x** |
| hot: COUNT(*) of 20k rows | DRIVER | 1.7x | 1.7x |  |

#### MYSQL, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DLJ | 0.9x | 0.9x |  |
| non-key filter (100 rows) | DRIVER | 1.7x | 1.7x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.6x | 1.6x |  |
| COUNT by FK | DRIVER | 1.6x | 1.6x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.3x | 1.6x |  |
| key JOIN FK, both ScalarDB | DRIVER | 1.7x | 1.7x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 3.0x | 3.0x |  |
| IN subquery on non-key | DRIVER | 45.4x | 45.4x |  |
| IN subquery selecting a non-key | DRIVER | 64.7x | 64.7x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 2.6x | 2.6x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 2.9x | 2.9x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPA | 7.0x | 9.2x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 35.1x | 35.1x |  |
| full scan: SUM of 500k rows | SPLIT_PA | 4.1x | 5.2x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PA | 5.5x | 7.4x |  |
| full scan: unindexed filter | SPLIT_DAJH | 1.9x | 2.6x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 17.8x | 17.8x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 5.8x | 5.8x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_DMX | 2.7x | 3.6x |  |
| 3 tables: key lookup (5 rows) | DRIVER | 3.4x | 3.4x |  |
| 3 tables: one region (500 rows) | DRIVER | 32.1x | 32.1x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 7.4x | 7.4x |  |
| 4 tables: one region (2.5k rows) | SPLIT_LT | 23.6x | 29.1x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.4x | 115.5x | **83.4x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.2x | 25.7x | **3.6x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.6x | 631.1x | **406.7x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 25.7x | **23.2x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.7x | 813.2x | **487.9x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.2x | 27.5x | **22.2x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DAJP | 46.7x | 61.3x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.2x | 1.3x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.8x | 1.8x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.5x | 2.5x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 4.1x | 7.1x | **1.7x** |
| composite key: full-key lookup | SPLIT_LJ | 1.2x | 1.4x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.3x | 1.6x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 2.7x | 2.7x |  |
| hot: key lookup | SPLIT_LJ | 1.2x | 1.3x |  |
| hot: indexed filter (100 rows) | DRIVER | 2.6x | 2.6x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.4x | 1.8x |  |
| hot: COUNT(*) of 20k rows | SPLIT_PA | 11.6x | 17.6x | **1.5x** |

#### POSTGRESQL, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | VIEW_AJ | 0.9x | 1.2x |  |
| non-key filter (100 rows) | SPLIT_NI | 1.1x | 1.1x |  |
| FK filter, ORDER BY, LIMIT 3 | VIEW_AJ | 1.1x | 1.2x |  |
| COUNT by FK | VIEW_AJ | 1.1x | 1.1x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.1x | 1.2x |  |
| key JOIN FK, both ScalarDB | VIEW_AJ | 1.2x | 1.2x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 1.4x | 1.4x |  |
| IN subquery on non-key | DRIVER | 1.8x | 1.8x |  |
| IN subquery selecting a non-key | DRIVER | 2.3x | 2.3x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 1.3x | 1.3x |  |
| LEFT JOIN, ScalarDB on both sides | VIEW_AJ | 1.3x | 1.5x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_MX | 2.1x | 3.5x | **1.6x** |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 1.6x | 1.6x |  |
| full scan: SUM of 500k rows | SPLIT_AJ | 1.4x | 11.4x | **8.0x** |
| full scan: GROUP BY of 100k rows | SPLIT_NI | 1.7x | 3.9x | **2.3x** |
| full scan: unindexed filter | VIEW_LJ | 1.0x | 1.0x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | SPLIT_NI | 2.0x | 2.2x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_PA | 1.3x | 1.4x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_PA | 1.1x | 1.2x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LT | 1.7x | 1.8x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 1.8x | 1.8x |  |
| 4 tables: key lookup (25 rows) | VIEW_AJ | 2.1x | 3.2x | **1.5x** |
| 4 tables: one region (2.5k rows) | DRIVER | 2.2x | 2.2x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.1x | 1.1x |  |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DAJN | 1.3x | 1.3x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | SPLIT_DHDP | 3.8x | 4.1x |  |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.2x | 1.5x |  |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | DRIVER | 14.6x | 14.6x |  |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.1x | 4.1x | **3.6x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_NI | 1.8x | 4.2x | **2.3x** |
| wide: key lookup, 2 of 50 columns | VIEW_CASE | 1.1x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | SPLIT_AJ | 1.2x | 1.3x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.3x | 2.3x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 1.2x | 4.2x | **3.5x** |
| composite key: full-key lookup | VIEW_AJ | 1.0x | 1.2x |  |
| composite key: partition scan (10 rows) | SPLIT_AJ | 1.1x | 1.1x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 2.3x | 2.3x |  |
| hot: key lookup | VIEW_AJ | 1.1x | 1.1x |  |
| hot: indexed filter (100 rows) | SPLIT_AJ | 1.2x | 1.2x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.4x |  |
| hot: COUNT(*) of 20k rows | SPLIT_PA | 2.7x | 3.3x |  |

#### POSTGRESQL, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | VIEW_CASE | 0.9x | 1.1x |  |
| non-key filter (100 rows) | DRIVER | 2.5x | 2.5x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.3x | 1.3x |  |
| COUNT by FK | DRIVER | 1.1x | 1.1x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.1x | 1.2x |  |
| key JOIN FK, both ScalarDB | DRIVER | 1.3x | 1.3x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 5.0x | 5.0x |  |
| IN subquery on non-key | DRIVER | 9.6x | 9.6x |  |
| IN subquery selecting a non-key | DRIVER | 7.6x | 7.6x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 4.5x | 4.5x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 1.5x | 1.5x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DRV | 3.2x | 5.0x | **1.6x** |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 9.1x | 9.1x |  |
| full scan: SUM of 500k rows | SPLIT_DRVN | 2.0x | 12.1x | **5.9x** |
| full scan: GROUP BY of 100k rows | SPLIT_DPA | 2.1x | 2.7x |  |
| full scan: unindexed filter | VIEW_AJ | 1.2x | 1.2x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 5.5x | 5.5x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 2.5x | 2.5x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_NI | 1.2x | 1.9x | **1.6x** |
| 3 tables: key lookup (5 rows) | DRIVER | 1.9x | 1.9x |  |
| 3 tables: one region (500 rows) | DRIVER | 17.6x | 17.6x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 3.3x | 3.3x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 8.1x | 8.1x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.1x | 1.1x |  |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DAJN | 9.8x | 10.0x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | SPLIT_DAJN | 64.2x | 472.5x | **7.4x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | DRIVER | 24.2x | 24.2x |  |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | DRIVER | 102.7x | 102.7x |  |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.1x | 1.3x |  |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DHDP | 5.5x | 8.5x | **1.5x** |
| wide: key lookup, 2 of 50 columns | VIEW_CASE | 1.1x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 2.9x | 2.9x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 7.3x | 7.3x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 1.2x | 3.9x | **3.3x** |
| composite key: full-key lookup | VIEW_CASE | 1.0x | 1.1x |  |
| composite key: partition scan (10 rows) | SPLIT_DRVN | 1.0x | 1.1x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 8.6x | 8.6x |  |
| hot: key lookup | VIEW_CASE | 1.0x | 1.1x |  |
| hot: indexed filter (100 rows) | DRIVER | 3.4x | 3.4x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.3x |  |
| hot: COUNT(*) of 20k rows | VIEW_CASE | 1.6x | 2.3x |  |

### Tier B (RDS)


#### MARIADB, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DAJ | 1.2x | 1.6x |  |
| non-key filter (100 rows) | DRIVER | 1.6x | 1.6x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.9x | 1.9x |  |
| COUNT by FK | DRIVER | 1.8x | 1.8x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.3x | 1.8x |  |
| key JOIN FK, both ScalarDB | VIEW_LJ | 2.3x | 2.7x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_HD | 1.6x | 1.7x |  |
| IN subquery on non-key | DRIVER | 2.9x | 2.9x |  |
| IN subquery selecting a non-key | DRIVER | 3.4x | 3.4x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 2.0x | 2.0x |  |
| LEFT JOIN, ScalarDB on both sides | SPLIT_LJ | 3.3x | 4.4x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPH | 2.0x | 2.0x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 2.2x | 2.2x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 1.8x | 1.9x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 2.0x | 2.0x |  |
| full scan: unindexed filter | SPLIT_HJ | 1.0x | 1.1x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 2.1x | 2.1x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_PH | 1.6x | 1.8x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_HD | 1.4x | 1.5x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LJ | 3.4x | 5.0x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 2.7x | 3.2x |  |
| 4 tables: key lookup (25 rows) | VIEW_AJ | 5.2x | 10.1x | **1.9x** |
| 4 tables: one region (2.5k rows) | SPLIT_LH | 2.3x | 3.2x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.4x | 3.1x | **2.1x** |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DHDP | 3.4x | 3.8x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 1.3x | **612.7x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.4x | 2.2x | **1.6x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 1.4x | **529.9x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.3x | 3.9x | **2.9x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DRVP | 24.5x | 32.6x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.3x | 1.7x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.5x | 1.5x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.5x | 2.5x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_LJ | 3.9x | 11.3x | **2.9x** |
| composite key: full-key lookup | SPLIT_LJ | 1.2x | 1.7x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.4x | 1.9x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.2x | 3.2x |  |
| hot: key lookup | SPLIT_LJ | 1.2x | 1.6x |  |
| hot: indexed filter (100 rows) | DRIVER | 1.7x | 1.7x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.4x | 2.7x | **1.9x** |
| hot: COUNT(*) of 20k rows | SPLIT_PH | 2.0x | 2.0x |  |

#### MARIADB, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_LJ | 1.2x | 1.4x |  |
| non-key filter (100 rows) | DRIVER | 2.0x | 2.0x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.7x | 1.7x |  |
| COUNT by FK | DRIVER | 1.6x | 1.6x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.3x | 1.6x |  |
| key JOIN FK, both ScalarDB | DRIVER | 2.3x | 2.3x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 6.3x | 6.3x |  |
| IN subquery on non-key | DRIVER | 10.6x | 10.6x |  |
| IN subquery selecting a non-key | DRIVER | 15.2x | 15.2x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 2.1x | 2.1x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 3.6x | 3.6x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPH | 4.0x | 4.2x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 7.0x | 7.0x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 2.3x | 2.3x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 3.9x | 4.1x |  |
| full scan: unindexed filter | SPLIT_LJ | 1.8x | 2.2x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 25.6x | 25.6x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 7.6x | 7.6x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_DRV | 2.4x | 4.6x | **1.9x** |
| 3 tables: key lookup (5 rows) | DRIVER | 4.5x | 4.5x |  |
| 3 tables: one region (500 rows) | DRIVER | 6.7x | 6.7x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 9.0x | 9.0x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 9.3x | 9.3x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.4x | 15.0x | **10.7x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.0x | 26.2x | **3.7x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 2.4x | **1149.3x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.4x | 3.5x | **2.5x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 0.0x | 2.9x | **1158.8x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.3x | 3.3x | **2.5x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DRVP | 43.1x | 53.6x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.3x | 1.3x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.8x | 1.8x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 3.0x | 3.0x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_LJ | 3.9x | 8.9x | **2.3x** |
| composite key: full-key lookup | VIEW_CASE | 1.3x | 1.4x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.4x | 1.6x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.7x | 3.7x |  |
| hot: key lookup | VIEW_CASE | 1.2x | 1.3x |  |
| hot: indexed filter (100 rows) | DRIVER | 2.7x | 2.7x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.5x | 2.2x |  |
| hot: COUNT(*) of 20k rows | SPLIT_DPH | 8.1x | 9.0x |  |

#### MYSQL, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DLJ | 0.9x | 1.3x |  |
| non-key filter (100 rows) | DRIVER | 1.6x | 1.6x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.7x | 1.7x |  |
| COUNT by FK | DRIVER | 1.8x | 1.8x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.3x | 1.9x |  |
| key JOIN FK, both ScalarDB | SPLIT_LJ | 2.7x | 2.9x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_MH | 1.7x | 1.8x |  |
| IN subquery on non-key | SPLIT_MH | 2.4x | 2.5x |  |
| IN subquery selecting a non-key | DRIVER | 3.5x | 3.5x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 36.7x | 36.7x |  |
| LEFT JOIN, ScalarDB on both sides | SPLIT_LJ | 3.8x | 5.3x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_PH | 1.6x | 1.6x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 2.0x | 2.0x |  |
| full scan: SUM of 500k rows | SPLIT_PH | 1.5x | 1.6x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PH | 2.5x | 3.0x |  |
| full scan: unindexed filter | SPLIT_DLJ | 1.0x | 1.0x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 1.9x | 1.9x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_HD | 1.4x | 1.6x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_MH | 1.1x | 1.2x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LJ | 3.8x | 5.1x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 2.5x | 2.6x |  |
| 4 tables: key lookup (25 rows) | SPLIT_LT | 5.9x | 10.6x | **1.8x** |
| 4 tables: one region (2.5k rows) | SPLIT_LH | 2.3x | 3.1x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.5x | 3.8x | **2.5x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.6x | 95.3x | **12.6x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.7x | 15.8x | **9.3x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 1.5x |  |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.9x | 4457.2x | **2381.3x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.3x | 3.4x | **2.5x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DRVP | 21.6x | 24.3x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.2x | 1.7x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.5x | 1.5x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.3x | 2.3x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 4.6x | 12.4x | **2.7x** |
| composite key: full-key lookup | VIEW_CASE | 1.3x | 2.0x | **1.5x** |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.4x | 2.0x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.2x | 3.2x |  |
| hot: key lookup | SPLIT_LJ | 1.2x | 1.7x |  |
| hot: indexed filter (100 rows) | DRIVER | 1.7x | 1.7x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.4x | 2.9x | **2.0x** |
| hot: COUNT(*) of 20k rows | DRIVER | 1.7x | 1.7x |  |

#### MYSQL, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_LJ | 0.9x | 1.0x |  |
| non-key filter (100 rows) | DRIVER | 2.1x | 2.1x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.7x | 1.7x |  |
| COUNT by FK | DRIVER | 1.7x | 1.7x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.4x | 1.7x |  |
| key JOIN FK, both ScalarDB | DRIVER | 2.3x | 2.3x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 3.5x | 3.5x |  |
| IN subquery on non-key | DRIVER | 45.7x | 45.7x |  |
| IN subquery selecting a non-key | DRIVER | 80.3x | 80.3x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 2.8x | 2.8x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 3.6x | 3.6x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_PA | 6.1x | 7.6x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 36.0x | 36.0x |  |
| full scan: SUM of 500k rows | SPLIT_PA | 3.5x | 4.2x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PA | 5.1x | 6.3x |  |
| full scan: unindexed filter | SPLIT_NI | 1.7x | 2.2x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 25.2x | 25.2x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 6.5x | 6.5x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_DMX | 2.9x | 3.9x |  |
| 3 tables: key lookup (5 rows) | DRIVER | 4.4x | 4.4x |  |
| 3 tables: one region (500 rows) | DRIVER | 34.8x | 34.8x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 8.9x | 8.9x |  |
| 4 tables: one region (2.5k rows) | SPLIT_LT | 28.9x | 35.9x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.5x | 165.1x | **111.3x** |
| page: ORDER BY key DESC, 20 rows after 10k | VIEW_CASE | 7.4x | 25.7x | **3.5x** |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.7x | 854.5x | **498.4x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 26.1x | **24.0x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.9x | 5408.9x | **2815.6x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.4x | 40.2x | **29.2x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DAJP | 53.2x | 68.4x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_LJ | 1.2x | 1.4x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 2.0x | 2.0x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.6x | 2.6x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 4.6x | 8.6x | **1.9x** |
| composite key: full-key lookup | VIEW_CASE | 1.3x | 1.4x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.4x | 1.8x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 3.2x | 3.2x |  |
| hot: key lookup | VIEW_CASE | 1.2x | 1.4x |  |
| hot: indexed filter (100 rows) | DRIVER | 3.0x | 3.0x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.4x | 2.1x |  |
| hot: COUNT(*) of 20k rows | SPLIT_DPA | 10.9x | 15.7x |  |

#### ORACLE, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_DRV | 0.8x | 1.0x |  |
| non-key filter (100 rows) | DRIVER | 1.3x | 1.3x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.2x | 1.2x |  |
| COUNT by FK | DRIVER | 1.2x | 1.2x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.0x | 1.1x |  |
| key JOIN FK, both ScalarDB | DRIVER | 1.3x | 1.3x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 1.2x | 1.2x |  |
| IN subquery on non-key | SPLIT_PA | 0.2x | 0.2x |  |
| IN subquery selecting a non-key | DRIVER | 4.0x | 4.0x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 3.7x | 3.7x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 1.4x | 1.4x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_DPA | 2.3x | 2.3x |  |
| non-key filter JOIN FK, both ScalarDB | SPLIT_DPA | 0.2x | 0.2x |  |
| full scan: SUM of 500k rows | SPLIT_DPA | 1.5x | 1.5x |  |
| full scan: GROUP BY of 100k rows | SPLIT_PA | 1.3x | 1.4x |  |
| full scan: unindexed filter | SPLIT_NI | 1.0x | 1.0x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 0.2x | 0.2x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_DPH | 1.3x | 1.4x |  |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_DMX | 1.1x | 1.2x |  |
| 3 tables: key lookup (5 rows) | DRIVER | 1.4x | 1.4x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 0.1x | 0.2x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 1.4x | 1.4x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 0.3x | 0.3x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.1x | 1.5x |  |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DRVR | 31.2x | 32.2x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 4.6x | **4.2x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 39.7x | **36.0x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | DRIVER | 55.6x | 55.6x |  |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.1x | 10.8x | **9.7x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DAJN | 1.4x | 1.8x |  |
| wide: key lookup, 2 of 50 columns | VIEW_LJ | 1.1x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 1.2x | 1.2x |  |
| wide: 2 tables JOIN on key (100 rows) | SPLIT_AJ | 1.7x | 1.9x |  |
| wide: 4 tables JOIN on key, key lookup | SPLIT_MH | 1.6x | 1.6x |  |
| composite key: full-key lookup | VIEW_CASE | 1.0x | 1.1x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.3x | 1.4x |  |
| composite key: JOIN on the full key (100 rows) | SPLIT_MX | 1.7x | 2.0x |  |
| hot: key lookup | VIEW_LJ | 1.1x | 1.1x |  |
| hot: indexed filter (100 rows) | SPLIT_AJ | 2.8x | 3.4x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.2x |  |
| hot: COUNT(*) of 20k rows | SPLIT_PA | 7.7x | 13.1x | **1.7x** |

#### ORACLE, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_PH | 0.5x | 0.6x |  |
| non-key filter (100 rows) | DRIVER | 2.6x | 2.6x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.2x | 1.2x |  |
| COUNT by FK | DRIVER | 1.3x | 1.3x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.0x | 1.1x |  |
| key JOIN FK, both ScalarDB | DRIVER | 1.2x | 1.2x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_NI | 3.3x | 4.3x |  |
| IN subquery on non-key | DRIVER | 0.3x | 0.3x |  |
| IN subquery selecting a non-key | DRIVER | 6.5x | 6.5x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 5.9x | 5.9x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 1.4x | 1.4x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_PA | 6.0x | 6.1x |  |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 0.3x | 0.3x |  |
| full scan: SUM of 500k rows | SPLIT_PA | 2.2x | 2.4x |  |
| full scan: GROUP BY of 100k rows | SPLIT_DPA | 2.8x | 2.9x |  |
| full scan: unindexed filter | VIEW_AJ | 3.5x | 3.7x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 0.4x | 0.4x |  |
| many rows: JOIN FK, 3k customers (15k rows) | DRIVER | 1.5x | 1.5x |  |
| many rows: JOIN FK, 30k customers (150k rows) | VIEW_AJ | 1.4x | 2.7x | **1.9x** |
| 3 tables: key lookup (5 rows) | DRIVER | 1.6x | 1.6x |  |
| 3 tables: one region (500 rows) | DRIVER | 0.3x | 0.3x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 1.5x | 1.5x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 0.4x | 0.4x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.0x | 1.4x |  |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DRVR | 43.1x | 44.1x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.3x | 204.8x | **163.2x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.1x | 40.7x | **37.5x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_CASE | 77.7x | 338.2x | **4.3x** |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.1x | 28.2x | **26.5x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DAJN | 3.2x | 3.5x |  |
| wide: key lookup, 2 of 50 columns | VIEW_LJ | 1.1x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 2.1x | 2.1x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 4.3x | 4.3x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 1.1x | 1.4x |  |
| composite key: full-key lookup | VIEW_LJ | 1.0x | 1.0x |  |
| composite key: partition scan (10 rows) | VIEW_CASE | 1.3x | 1.3x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 5.0x | 5.0x |  |
| hot: key lookup | SPLIT_PA | 0.8x | 0.8x |  |
| hot: indexed filter (100 rows) | DRIVER | 3.3x | 3.3x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.2x |  |
| hot: COUNT(*) of 20k rows | VIEW_CASE | 16.6x | 36.7x | **2.2x** |

#### POSTGRESQL, 100 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_MH | 0.9x | 1.3x |  |
| non-key filter (100 rows) | SPLIT_AJ | 1.0x | 1.1x |  |
| FK filter, ORDER BY, LIMIT 3 | SPLIT_PA | 1.1x | 1.1x |  |
| COUNT by FK | SPLIT_AJ | 1.2x | 1.2x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.1x | 1.2x |  |
| key JOIN FK, both ScalarDB | VIEW_AJ | 1.3x | 1.3x |  |
| plain table JOIN on non-key (1000 rows) | SPLIT_PA | 1.0x | 1.1x |  |
| IN subquery on non-key | DRIVER | 1.3x | 1.3x |  |
| IN subquery selecting a non-key | DRIVER | 1.9x | 1.9x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 1.3x | 1.3x |  |
| LEFT JOIN, ScalarDB on both sides | VIEW_LJ | 1.3x | 1.7x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_MX | 1.9x | 3.1x | **1.6x** |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 1.4x | 1.4x |  |
| full scan: SUM of 500k rows | SPLIT_MH | 1.3x | 3.4x | **2.6x** |
| full scan: GROUP BY of 100k rows | SPLIT_PA | 1.5x | 1.5x |  |
| full scan: unindexed filter | SPLIT_LJ | 1.0x | 1.0x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 2.1x | 2.1x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_DRV | 0.6x | 1.1x | **2.0x** |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_HJ | 1.1x | 1.3x |  |
| 3 tables: key lookup (5 rows) | SPLIT_LT | 1.7x | 1.9x |  |
| 3 tables: one region (500 rows) | SPLIT_LH | 1.7x | 1.8x |  |
| 4 tables: key lookup (25 rows) | VIEW_AJ | 2.1x | 3.4x | **1.7x** |
| 4 tables: one region (2.5k rows) | DRIVER | 2.5x | 2.5x |  |
| page: ORDER BY key DESC, 20 rows of 500k | VIEW_CASE | 1.1x | 1.9x | **1.7x** |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DAJP | 1.4x | 1.4x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | SPLIT_DHDP | 2.9x | 3.0x |  |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.2x | 1.4x |  |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | DRIVER | 16.3x | 16.3x |  |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.1x | 3.7x | **3.4x** |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_DAJP | 1.5x | 1.6x |  |
| wide: key lookup, 2 of 50 columns | SPLIT_AJ | 1.0x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | SPLIT_NI | 1.2x | 1.2x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 2.2x | 2.2x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 1.2x | 3.8x | **3.2x** |
| composite key: full-key lookup | VIEW_CASE | 1.0x | 1.0x |  |
| composite key: partition scan (10 rows) | SPLIT_HJ | 1.0x | 1.1x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 2.2x | 2.2x |  |
| hot: key lookup | VIEW_CASE | 1.0x | 1.1x |  |
| hot: indexed filter (100 rows) | SPLIT_MX | 1.2x | 1.3x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.3x |  |
| hot: COUNT(*) of 20k rows | SPLIT_PA | 2.3x | 2.8x |  |

#### POSTGRESQL, 10000 rows in flight (1 runs)

| query | best form | best | DRIVER | flag |
|---|---|---|---|---|
| key lookup | SPLIT_HJ | 0.9x | 1.0x |  |
| non-key filter (100 rows) | DRIVER | 2.4x | 2.4x |  |
| FK filter, ORDER BY, LIMIT 3 | DRIVER | 1.1x | 1.1x |  |
| COUNT by FK | DRIVER | 1.2x | 1.2x |  |
| plain table JOIN on key (10 rows) | VIEW_CASE | 1.1x | 1.2x |  |
| key JOIN FK, both ScalarDB | DRIVER | 1.2x | 1.2x |  |
| plain table JOIN on non-key (1000 rows) | DRIVER | 1.4x | 1.4x |  |
| IN subquery on non-key | DRIVER | 9.9x | 9.9x |  |
| IN subquery selecting a non-key | DRIVER | 50.8x | 50.8x |  |
| NOT EXISTS, ScalarDB on both sides | DRIVER | 4.3x | 4.3x |  |
| LEFT JOIN, ScalarDB on both sides | DRIVER | 1.5x | 1.5x |  |
| full scan: COUNT(*) of 100k rows | SPLIT_HJ | 3.7x | 6.4x | **1.7x** |
| non-key filter JOIN FK, both ScalarDB | DRIVER | 9.7x | 9.7x |  |
| full scan: SUM of 500k rows | SPLIT_MX | 2.0x | 5.2x | **2.5x** |
| full scan: GROUP BY of 100k rows | SPLIT_NI | 1.9x | 2.6x |  |
| full scan: unindexed filter | DRIVER | 1.2x | 1.2x |  |
| many rows: JOIN FK, 300 customers (1.5k rows) | DRIVER | 5.5x | 5.5x |  |
| many rows: JOIN FK, 3k customers (15k rows) | SPLIT_PA | 1.4x | 2.3x | **1.6x** |
| many rows: JOIN FK, 30k customers (150k rows) | SPLIT_HD | 1.5x | 1.7x |  |
| 3 tables: key lookup (5 rows) | DRIVER | 1.8x | 1.8x |  |
| 3 tables: one region (500 rows) | DRIVER | 17.7x | 17.7x |  |
| 4 tables: key lookup (25 rows) | DRIVER | 3.2x | 3.2x |  |
| 4 tables: one region (2.5k rows) | DRIVER | 17.4x | 17.4x |  |
| page: ORDER BY key DESC, 20 rows of 500k | SPLIT_DRVP | 1.1x | 1.1x |  |
| page: ORDER BY key DESC, 20 rows after 10k | SPLIT_DAJP | 6.3x | 9.2x |  |
| page: JOIN FK, ORDER BY key DESC, 20 rows | SPLIT_DRVP | 137.9x | 490.5x | **3.6x** |
| page: keyset, key < ?, ORDER BY key DESC, 20 rows | VIEW_CASE | 1.2x | 17.3x | **14.1x** |
| page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows | VIEW_AJ | 621.5x | 731.5x |  |
| page: indexed range of 30k rows, ORDER BY key, 20 rows | VIEW_CASE | 1.2x | 1.3x |  |
| DISTINCT over an indexed column (1000 of 100k) | SPLIT_NI | 1.9x | 3.9x | **2.0x** |
| wide: key lookup, 2 of 50 columns | VIEW_CASE | 1.1x | 1.1x |  |
| wide: indexed filter, 1 of 50 columns (100 rows) | DRIVER | 3.0x | 3.0x |  |
| wide: 2 tables JOIN on key (100 rows) | DRIVER | 6.4x | 6.4x |  |
| wide: 4 tables JOIN on key, key lookup | VIEW_CASE | 1.2x | 3.7x | **3.2x** |
| composite key: full-key lookup | VIEW_CASE | 0.9x | 1.0x |  |
| composite key: partition scan (10 rows) | SPLIT_AJ | 1.0x | 1.0x |  |
| composite key: JOIN on the full key (100 rows) | DRIVER | 8.1x | 8.1x |  |
| hot: key lookup | VIEW_AJ | 1.1x | 1.1x |  |
| hot: indexed filter (100 rows) | DRIVER | 2.8x | 2.8x |  |
| hot: key JOIN from a customer | VIEW_CASE | 1.1x | 1.3x |  |
| hot: COUNT(*) of 20k rows | VIEW_CASE | 5.7x | 7.2x |  |
