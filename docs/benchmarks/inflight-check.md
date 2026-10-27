# The in-flight check of the per-table path (2026-10-21)

At SNAPSHOT, and at SERIALIZABLE on PostgreSQL, a read the row-by-row detector does not cover (a
grouped, aggregate or DISTINCT read, a join or subquery past it, an OFFSET, a scrollable result)
first checks each ScalarDB table it reaches for a row in flight, `SELECT 1 FROM T_meta WHERE
tx_state IN (1, 2)`, and aborts with `40001` if one is there (design 4.2, the per-table check).
That check ignores the read's predicate: a row in flight anywhere in the table aborts the read.
How often that aborts reads that a row in flight could not have changed, and what a check that
takes the predicate costs instead.

Benchmark: `driver/src/integrationTest/.../jdbc/InFlightCheckBenchmark.java`, run with
`./gradlew :driver:integrationTest --tests '*InFlightCheckBenchmark*' -Dnativejdbc.bench=true`;
`-Dnativejdbc.bench.arms=true` runs the plan diagnostics of the last section instead.

Setup: orders `o` (100k rows, 1,000 regions of 100, each order of a customer `cid`) and customers
`c` (10k rows; a region's orders reach 10 of them), imported into ScalarDB decoupled, ScalarDB
indexes on `o.region` and `o.cid`, the driver's setup run. Checks through the vendor drivers, so
only the SQL is measured: median of 300 executions with a random region, after a warm-up; local
containers on an Apple Silicon laptop, SQL Server under x86 emulation.

The checks:

- **table-wide** (today): the check above, per table.
- **predicate**, one table: the read's WHERE over the before images UNION ALL over the after images
  of the rows in flight, as the in-flight check of a locking read (`Router.inFlightStep`).
- **own conjuncts**, a join: per table the conjuncts naming it alone (`o.region = ?`), a table with
  none checked whole (`c`).
- **in-flight pairs**, a join: the statement as `SELECT 1`, over every combination of pieces with a
  row in flight, each by its after or before image: `(o after, c data)`, `(o before, c data)`,
  `(o data, c after)`, `(o data, c before)`, `(o before, c before)`, the data table holding the
  committed rows and the after images. A correlated `EXISTS (SELECT 1 FROM c WHERE c.id = o.cid)`
  takes the same check: a row of `c` in flight reaches it only through a pair the join matches.

Each check is correct: a row in flight that no image brings into the statement's rows changes
nothing, whichever image is true; a dropped conjunct only widens what is checked.

## Conclusions

1. **The table-wide check aborts most grouped reads once ScalarDB writes the table.** One writer
   (60-230 commits/s, split over the two tables) already aborts 22-28% of the one-table grouped
   reads and 44-53% of the joins, four writers 64-99%, sixteen 97-100%: a retry loop starves.
2. **The predicate check removes nearly all of it on one table**: 0-1.6% with `region = ?`, 0.1-11%
   with a range of 1% of the table; what remains is mostly a row in flight the read does reach.
   Cost over the table-wide check: 0-300 µs on PostgreSQL and MySQL, up to 1.1 ms on MariaDB and
   SQL Server, 2.1 ms for Oracle's range with few rows in flight; and it runs on every read, where the table-wide check runs once per
   table per transaction (`passed`).
3. **Own conjuncts do not help joins.** A table without a selective condition of its own, the
   usual lookup table or correlated subquery, is checked whole, and aborts as today: 23-26% at one
   writer, 64-98% at four.
4. **In-flight pairs do**: 0-3% at every writer count, about the single-table predicate check.
   Cost on PostgreSQL, MySQL, MariaDB and Oracle 0.4-2.4 ms, about one more read of the statement.
   SQL Server measured 16-18 ms with 100 rows in flight or fewer in the full run, 1.9-4.7 ms when
   the statement is first planned with rows in flight (diagnostics below): a plan made from the
   filtered in-flight index's statistics, taken with no row in flight, scans a metadata table. A table
   hint on the in-flight index bounded it by the rows in flight on the laptop, but gained nothing
   on EC2, where the 16-18 ms was the emulation's (below); the driver adds none on SQL Server. On
   Oracle 19c it adds the same index hint, for another reason (Oracle 19c's plan, below).
5. **Design that follows**: one table, the predicate check; a join or semi-joined subquery the
   split covers, the in-flight pairs check, built from the split read's fragments; the table-wide
   check kept for the shapes the split does not cover (a scalar subquery, a CTE, past the copy
   limit) and as the cheaper choice where ScalarDB does not write concurrently. Under SERIALIZABLE
   on MySQL, MariaDB and SQL Server the table-wide check stays: its locking read is what holds off
   every later prepare, and a predicate would lock the same range.
6. **On dedicated machines (AWS, last section)** the predicate check holds on PostgreSQL, MySQL
   and MariaDB, ScalarDB's own commits unchanged. On Oracle 19c (RDS) it took about 300 ms a read
   with rows in flight and lowered both reads and ScalarDB's commits: its plan drove the before
   images from the data table and probed their index once per row. Read from the metadata table
   alone, the check takes 0.7 ms there, and reads gain with ScalarDB's commits unchanged, as on the
   others. On Oracle 23 Free (2 CPUs) ScalarDB's commits fall about 30% with it: the cost where the
   database is short of CPU (below).

## Aborts with live ScalarDB writers

ScalarDB transactions updating one random row of `o` or `c` each, w writer threads, 10 s, while one
reader runs the checks in turn; the share of checks that find a row, which is the share of reads
that abort. Writers at 1 / 4 / 16.

One table, `SELECT region, SUM(amount) FROM o WHERE <region> GROUP BY region`:

| engine | commits/s | table-wide (today) | `region = ?` | `region` in 10 (1%) |
|---|---|---|---|---|
| PostgreSQL | 234 / 761 / 1,773 | 28 / 70 / 99% | 0.0 / 0.1 / 0.2% | 0.2 / 0.5 / 3.7% |
| MySQL | 75 / 235 / 581 | 25 / 71 / 97% | 0.0 / 0.1 / 0.3% | 0.3 / 0.9 / 3.6% |
| MariaDB | 75 / 227 / 562 | 22 / 64 / 97% | 0.0 / 0.1 / 0.7% | 0.1 / 1.1 / 5.4% |
| SQL Server | 62 / 177 / 359 | 27 / 69 / 99% | 0.2 / 0.0 / 0.4% | 0.2 / 1.2 / 4.8% |
| Oracle | 119 / 304 / 735 | 26 / 87 / 100% | 0.0 / 0.0 / 1.6% | 0.3 / 2.7 / 11% |

Two tables, `o JOIN c ON c.id = o.cid` or `o WHERE EXISTS (... c.id = o.cid)`, `WHERE o.region = ?
GROUP BY o.region`:

| engine | table-wide `o`, `c` (today) | own conjuncts | in-flight pairs |
|---|---|---|---|
| PostgreSQL | 44 / 92 / 100% | 23 / 64 / 99% | 0.0 / 0.2 / 0.8% |
| MySQL | 49 / 94 / 100% | 25 / 71 / 98% | 0.1 / 0.4 / 0.8% |
| MariaDB | 47 / 86 / 100% | 24 / 65 / 96% | 0.1 / 0.3 / 0.9% |
| SQL Server | 53 / 93 / 100% | 26 / 71 / 98% | 0.0 / 0.1 / 0.0% |
| Oracle | 53 / 99 / 100% | 24 / 98 / 100% | 0.0 / 0.8 / 3.1% |

An earlier run with writers on `o` alone gave the one-table checks 45-52% / 79-99% / 100% for
the table-wide check and the same order for the others.

## Cost per check, rows in flight held fixed

Rows in flight set in the metadata tables alone, spread over the regions and customers, their
before images the rows' own values. Median µs at 0 / 10 / 100 / 1,000 / 10,000 rows in flight per
table; the share of checks that find a row matched the expected one at every count (1% and 18% for
the pairs at 10 and 100; at 1,000 every region holds a row in flight and every form aborts).

One table:

| engine | table-wide | `region = ?` | `region` in 10 | the read, `region = ?` |
|---|---|---|---|---|
| PostgreSQL | 390-930 (5,580-5,710 at 0 and 10) | 420-640 | 390-650 | 400-480 |
| MySQL | 440-490 | 490-610 | 500-570 | 500-610 |
| MariaDB | 370-930 | 580-1,090 | 530-1,470 | 460-660 |
| SQL Server | 980-1,170 | 1,180-2,250 | 1,160-2,210 | 1,200-1,420 |
| Oracle | 390-510 | 430-730 | 420-2,580 | 500-530 |

Two tables:

| engine | table-wide | own conjuncts | in-flight pairs | the join read |
|---|---|---|---|---|
| PostgreSQL | 400-420 | 400-540 | 410-1,310 | 450-540 |
| MySQL | 460-610 | 500-610 | 620-2,420 | 590-1,180 |
| MariaDB | 590-1,530 | 700-970 | 870-1,550 | 520-940 |
| SQL Server | 600-2,240 | 610-2,350 | 690-18,100 | 2,750-2,930 |
| Oracle | 410-760 | 440-1,020 | 450-1,850 | 530-570 |

Outliers of the table-wide check: PostgreSQL 5.6-5.7 ms at 0 and 10 rows in flight in the last
run (0.4-0.5 ms in the run before); MySQL 8.6 ms at 0 rows in flight in two runs, 0.46 ms in the
third. Both came right after the setup's backfill wrote every metadata row; MySQL's is likely the
full scan of the in-flight index under `LIMIT 1` of the diagnostics below. Not investigated
further.

## Plan diagnostics of the in-flight pairs (`-Dnativejdbc.bench.arms=true`)

At 10 / 100 rows in flight per table, the check written four ways, median µs (PostgreSQL not run:
the diagnostic's `FROM DUAL` failed there, since fixed):

| engine | UNION ALL, row limit | OR'ed EXISTS | UNION ALL, no limit | separate statements |
|---|---|---|---|---|
| MySQL | 12,505 / 4,964 | 12,139 / 4,990 | 12,282 / 2,388 | 5,579 / 4,317 |
| MariaDB | 815 / 1,165 | 788 / 1,174 | 753 / 1,256 | 2,481 / 2,918 |
| SQL Server | 1,991 / 4,681 | 1,919 / 4,766 | 1,922 / 4,861 | 4,599 / 7,460 |
| Oracle | 1,064 / 915 | 1,025 / 934 | 992 / 958 | 2,724 / 2,593 |

- **The writing makes no difference**; separate statements pay a round trip each.
- **MySQL** plans the arm `(o after, c data)` with `LIMIT 1` as a full scan of the in-flight index
  (`type: index`, 9.8 ms; 1.9 ms without the limit), and `(o data, c before)` as a scan of `c`'s
  metadata table (2.6 ms); its estimate of the rows in flight is 1, from statistics taken with
  none. Not solved here: an index hint on the in-flight index, or the histogram the setup takes, is
  the next thing to measure.
- **SQL Server**: 1.9 / 4.7 ms here against 16.5 / 18.1 ms in the full run, the same statement and
  rows in flight; the full run first planned it with no row in flight. `OPTION (RECOMPILE)` makes
  each arm 8-17 ms, its compile alone.

## SQL Server with stale statistics (`-Dnativejdbc.bench.coldPlan=true`)

The setup builds the in-flight index filtered on `tx_state IN (1, 2)` while no row is in flight,
and SQL Server rarely updates a filtered index's statistics. Here: statistics updated and the plan
cache cleared with no row in flight, the check run (planned) there, then run with 100 rows in
flight per table on that plan; then planned again after `UPDATE STATISTICS`. Median µs, the
laptop's SQL Server under x86 emulation (`build/bench/inflight-coldplan-sqlserver.txt`):

| check | no row in flight | 100 in flight, stale plan | 100 in flight, statistics updated |
|---|---|---|---|
| no hint | 736-1,056 | 4,801-5,392 | 2,587-3,035 |
| `OPTION (RECOMPILE)` | 22,318-22,672 | 28,198-28,403 | 25,166-29,239 |
| `OPTION (LOOP JOIN)` | 767-771 | 4,862-5,075 | 5,462-5,501 |
| `WITH (FORCESEEK)` on the metadata tables | 1,694-1,986 | 1,744-1,939 | 1,527-1,529 |
| `WITH (INDEX(<in-flight index>))`, the driver's | 743 | 5,653 | 1,875 |

- **No hint**: the stale plan reads `c`'s metadata table whole for the `(o data, c before)` arm
  and hashes `o`'s in-flight index against its `before_region` index: a cost that grows with the
  table.
- **`FORCESEEK`** is fastest, but fails where no index gives a seek: the join test's tables, with
  no index on `before_region`, fail with it (error 8622), which the driver takes as an abort.
- **The in-flight index hint**, which the driver used for a while: on the stale plan every arm starts from the
  in-flight index, about 100 key lookups an arm, so the cost follows the rows in flight, never the
  table; with updated statistics it is as good as the best. The 5.7 ms is inflated by the
  emulation; on EC2 it gained nothing (run `20261023-1317`, below).

## Joins beside ScalarDB writers on Oracle and SQL Server (laptop)

`-Dnativejdbc.bench.writers=true -Dnativejdbc.bench.writersRead=join`, branch
`pairs-oracle-sqlserver`, one run each; reads committed/s, check off → on, and ScalarDB's commits
off → on:

| engine | 1 writer | 4 writers | 16 writers | ScalarDB's commits at 4 / 16 |
|---|---|---|---|---|
| Oracle 23 Free | 444 → 427 | 11 → 280 | 0 → 126 | 361 → 264 / 763 → 432 |
| SQL Server 2022 (emulated) | 260 → 366 | 51 → 309 | 0 → 54 | 168 → 173 / 394 → 251 |

Retries fall to 0.08 per commit or fewer. ScalarDB's commits fall on both where reads now finish
beside them, as with the one-table check on Oracle 23 Free (2 CPUs) and on this laptop's emulated
SQL Server; on dedicated machines that is to be measured.

On AWS, run `20261023-1231` (SQL Server 2022 Developer on EC2 in Docker at 1.5 GB, Oracle 19c SE2
on RDS db.m5.2xlarge; two runs each), reads committed/s, check off → on:

| engine | 1 writer | 4 writers | 16 writers | ScalarDB's commits at 4 / 16 |
|---|---|---|---|---|
| SQL Server (EC2) | 722-761 → 826-898 | 55-80 → 104-136 | 0 → 8-14 | 639-653 → 569-578 / 2,120-2,192 → 1,657-1,711 |
| Oracle 19c (RDS) | 374-391 → 203-210 | 0-2 → 97 | 0 → 78 | 616-624 → 505-506 / 1,880-1,947 → 1,156-1,238 |

- **Neither gains as PostgreSQL and MariaDB do** (1,514-1,575 and 427-462 at four writers on EC2).
- **SQL Server**: reads gain 70-90% at four writers, but at sixteen p50 is 224-360 ms and p99 2-3 s;
  ScalarDB's commits 11% lower at four, 20-24% at sixteen.
- **Oracle 19c loses at one writer**: 45% fewer reads, p50 10 → 16 ms, so the check costs about
  6 ms a read there; from four writers on, reads that never finished do. ScalarDB's commits 18%
  lower at four, 36-38% at sixteen.
- So the check's own cost on these two is to be measured on the AWS machines (the static mode, a
  fixed number of rows in flight, and the plans) before either takes this form: done below, with
  the fixes measured in `20261023-1850`.

### Why, on the AWS machines (run `20261023-1317`)

`-Dnativejdbc.bench.arms=true -Dnativejdbc.bench.coldPlan=true`, the same machines, rows in
flight held fixed, median µs:

| engine, rows in flight per table | the join read | the check | its arms 0-4 alone |
|---|---|---|---|
| SQL Server, 10 | 2,071 | 602 | 254 / 252 / 387 / 394 / 258 |
| SQL Server, 100 | 2,072 | 3,267 | 367 / 382 / 1,601 / 1,657 / 388 |
| Oracle 19c, 10 | 401 | 6,078 | 748 / **4,225** / 402 / 238 / 163 |
| Oracle 19c, 100 | 287 | 6,376 | 395 / **4,418** / 540 / 229 / 258 |

The arms: 0 `(o after, c data)`, 1 `(o before, c data)`, 2 `(o data, c after)`, 3 `(o data, c
before)`, 4 `(o before, c before)`.

- **Oracle 19c: one arm's plan.** `(o before, c data)` reads `C` whole (10,000 rows) and probes
  `o`'s metadata table by `before_CID` 10,000 times, where Oracle 23 starts from `o`'s before
  images in the region (`before_REGION`) and probes `C` by its key. The arm costs 4.2-4.4 ms, the
  check 6 ms, fifteen times the read: the 6 ms a read lost at one writer. Not for want of
  statistics, as first read from the plan's note on dynamic sampling (next section): the
  before-image columns are NULL but for the rows in flight, their indexes hold next to nothing,
  and 19c costs a probe of one as free.
- **SQL Server: not the check, mostly.** With 10 rows in flight, about what sixteen writers hold,
  the check costs 0.6 ms against the read's 2.1 ms; the reads' p50 of 224-360 ms beside sixteen
  writers comes from elsewhere, to be found with a breakdown of a read's time under writers. At
  100 the check is 3.3 ms: arms 2 and 3 start from `c`'s rows in flight and fan out to each
  customer's 10 orders (990 key lookups), where starting from the region's 100 orders is cheaper.
- **SQL Server's statistics, natively**: the index hint gains nothing on EC2 (3,295 against 3,349
  µs on the stale plan; 1,190 against 1,500 planned on updated statistics). The 16-18 ms on the
  laptop was the emulation's; `FORCESEEK` is again the fastest, 1.0-1.1 ms, and still not usable.
  The driver no longer adds the hint on SQL Server.

### Oracle 19c's plan on the laptop, and the driver's hint

`-Dnativejdbc.bench.arms=true` on Oracle 23 Free with
`-Dnativejdbc.bench.session="optimizer_features_enable='19.1.0' optimizer_dynamic_sampling=2"`,
19c's optimizer and its sampling: the same plan as on RDS, arm 1 driven from `C`. Median µs of the
check, as one UNION ALL under the row limit, and of arms 0-4 alone, by rows in flight per table:

| optimizer, pieces | 10: the check | arms | 100: the check | arms |
|---|---|---|---|---|
| 19c, unhinted | 5,379 | 633 / **3,181** / 660 / 581 / 478 | 5,107 | 606 / **3,188** / 635 / 514 / 492 |
| 19c, data tables' statistics gathered | 4,258 | 804 / **3,132** / 654 / 577 / 431 | 4,132 | 679 / **3,198** / 621 / 530 / 508 |
| 19c, `NO_MERGE` in the pieces in flight | 1,584 | 684 / 429 / 873 / 830 / 448 | 1,533 | 679 / 403 / 901 / 1,601 / 424 |
| 19c, `LEADING` the piece in flight | 4,912 | 692 / **3,028** / 635 / 537 / 410 | 5,045 | 731 / **3,151** / 630 / 532 / 510 |
| 19c, `INDEX(m, the in-flight index)` in the pieces | **778** | 571 / 429 / 574 / 548 / 440 | **930** | 548 / 468 / 592 / 1,295 / 509 |
| 23, unhinted | 1,145 | | 936 | |
| 23, `INDEX(m, the in-flight index)` in the pieces | 838 | 604 / 423 / 628 / 500 / 432 | 932 | 580 / 513 / 610 / 1,047 / 470 |

- **Statistics are not the cause**: gathered on both data tables (the setup's on the metadata
  tables are locked), the plan stays.
- **The in-flight index is**: read through it, each piece costs what its rows in flight do, and no
  plan probes the before images' near-empty indexes once per row of the other table. The driver
  now adds the hint on Oracle in the in-flight pairs' pieces (`SplitRead.inFlight` and
  `afterImages` with `hinted`), and only there: the split read's before-image piece wants the
  predicate's own index. On 23 the hint costs nothing.

### With the fixes, on the AWS machines (run `20261023-1850`)

The same machines and mode as `20261023-1231`, the driver with Oracle's in-flight index hint and
without SQL Server's, `-Dnativejdbc.bench.breakdown=true`; two runs each, reads committed/s, check
off → on (`20261023-1231`'s "on" in brackets):

| engine | 1 writer | 4 writers | 16 writers | ScalarDB's commits at 4 / 16 |
|---|---|---|---|---|
| SQL Server (EC2) | 713-759 → 809-864 (826-898) | 56-60 → 85-253 (104-136) | 0 → 9-12 (8-14) | 646-648 → 524-567 / 2,177-2,182 → 1,785-1,936 |
| Oracle 19c (RDS) | 365-373 → 298-307 (203-210) | 0 → 177-182 (97) | 0 → 136-138 (78) | 580-585 → 483-525 / 1,921-1,938 → 1,267-1,282 |

The readers' time by phase, mean ms per read with the check on (commits cost 0.1-1.5 ms
throughout):

| engine | 0 writers | 1 writer | 4 writers | 16 writers |
|---|---|---|---|---|
| SQL Server | 1.3 | 2.1-2.2 | 7.7-23 | 156-223 |
| Oracle 19c | 3.7-4.0 | 6.4-6.6 | 10.8-11.1 | 14.0-14.2 |

- **Oracle 19c, the hint works**: reads gain 1.8-1.9x at four writers and sixteen over the
  unhinted driver, and the loss at one writer shrinks from 45% to 18%: a read costs 2.1 ms more
  with the check, where it cost 6 ms. Its p99 at sixteen writers is 49 ms.
- **SQL Server, the read's own statement, not the check**: SQL Server's account of the
  statements (`sys.dm_exec_query_stats`, from a cleared plan cache) at four writers puts 12.5 s
  of the readers' 40 s in the split read's join statement under one cached plan alone, 38 ms and
  4,174 logical reads a run against 1.3 ms quiet, and 0.3 s in the check, 0.9 ms a run. At
  sixteen the statement's cached plans show 1-2 runs each, so it is compiled again and again, at
  144-288 ms and 29,000-272,000 logical reads a run, in parallel (`CXPACKET` 19 s): the metadata
  tables' statistics refresh as the writers churn them (`WAIT_ON_SYNC_STATISTICS_REFRESH`), and
  the plans made then scan. The plans themselves are yet to be captured. A split read's plan
  under churn, which the table-wide check never reached on a join: it aborted first.
- **Without SQL Server's hint nothing is lost**: the "on" figures are within the earlier ones'
  noise.

### SQL Server's split read of a join under churn (laptop)

The writers mode over the join with `-Dnativejdbc.bench.statsEveryMs=2000`: the metadata tables'
statistics refreshed every 2 s while the writers run, each as the automatic updates do (skipping
one kept with `NORECOMPUTE`), as EC2's writers, eight times as fast, set off; the breakdown saves
the split read's plans. Reads committed/s with the check on:

| variant | 1 writer | 4 writers | 16 writers | the read's statement at 16 |
|---|---|---|---|---|
| as it was | 485 | 305 | 66 | 18.6-24.5 ms, 2,444-3,777 logical reads |
| `FORCESEEK` in the committed rows' `NOT EXISTS`* | 461 | 299 | 150 | 1.5 ms, 1,025 |
| the in-flight index's statistics kept (`NORECOMPUTE`) | 485 | 298 | 93 | up to 9 ms |
| all the metadata tables' statistics kept | 504 | **46** | 186 | 1.4 ms; the check 36.8 ms at four |
| all kept, the pairs' pieces through the in-flight index | 496 | 262 | **181** | 1.5 ms; the check 0.5-1.2 ms |

\* Measured with the whole tables' statistics refreshed every 2 s, the others each statistic on its
own; without `FORCESEEK` that run gave 474 / 337 / 69.

- **The cause**: the committed rows of each table, `NOT EXISTS` on the in-flight index, seek it
  per row with nothing in flight; a plan compiled after a refresh estimates 3-5 rows in it and
  scans it per row instead. The index holds no row and 300-435 ghost records the churn left, which
  each scan walks. With four writers and fewer the refreshes are rarer and the plans mostly seek.
- **`FORCESEEK` costs large reads**: the committed rows of o, median µs (`seekCost`), 10 or 100
  rows in flight alike:

  | rows read | as planned | `FORCESEEK` | `LEFT JOIN` on the metadata key |
  |---|---|---|---|
  | 100 (a region) | 867-924 | 945-999 | 1,055-1,208 |
  | 1,000 (ten regions) | 15,227-15,542 | 15,809-16,072 | 35,373-35,591 |
  | 100,000 (the table) | 27,796-28,910 | 87,640-88,459 | 61,903-62,108 |

- **Kept statistics fix the read, not the check**: planned from statistics with nothing in
  flight, the pairs' pieces scan a metadata table (as in "SQL Server with stale statistics"
  above); through the in-flight index they do not. The driver now does both: the setup keeps the
  metadata tables' statistics on SQL Server (design section 6's setup), and the pieces read
  through the in-flight index there as on Oracle.

### SQL Server with the setup's kept statistics, on EC2 (run `20261023-2312`)

The driver of `sqlserver-join-plans`: the metadata tables' statistics kept by the setup, the
pairs' pieces through the in-flight index, the pairs on; SQL Server alone, the machines and mode of
`20261023-1850` (whose "on" is in brackets); two runs, reads committed/s, check off → on:

| 1 writer | 4 writers | 16 writers | ScalarDB's commits at 4 / 16 |
|---|---|---|---|
| 894-917 → 947-949 (809-864) | 139-147 → **425-449** (85-253) | 0 → 13-25 (9-12) | 788-808 → 668-792 / 2,247-2,269 → 1,710-2,106 |

- **Four writers, fixed**: one plan, compiled once, serves every read (1.5-2.0 ms, 914-975
  logical reads); p99 11-17 ms against 60-188. Reads 3x the table-wide check's.
- **Sixteen, not yet**: no recompile any more (one plan, 275-501 runs), but each read costs 69-120
  ms and 10,000-19,600 logical reads, with `PAGELATCH_SH` 15 s in one run. The in-flight indexes
  hold 1,380-2,250 ghost records at 2,000-2,300 ScalarDB commits a second, against 300-435 on the
  laptop; the plans were not collected (the client's `build/bench/plans`), so whether that plan
  seeks or scans the index is to be seen.

### Its plans at sixteen writers, and FORCESEEK, on EC2 (run `20261024-0023`)

As `20261023-2312`, the plans saved, then four and sixteen writers again with `FORCESEEK` in the
committed rows' `NOT EXISTS` (`-Dnativejdbc.bench.seekToo=true`); two runs, reads committed/s with
the check on, mean ms a read:

| writers | kept statistics | kept statistics and `FORCESEEK` |
|---|---|---|
| 4 | 372-525, 3.6-5.1 ms | 184-365, 5.3-10.0 ms |
| 16 | 13-26, 74-145 ms | **189-271**, 7.1-9.9 ms |

- **The plan at sixteen still scans one index**: `o`'s in-flight index sought (estimate 1), `c`'s
  scanned per row (estimate 4-6), in every plan compiled at sixteen writers; at four both are
  sought. `c`'s metadata table, 10,000 rows, takes half the writers' 2,100-2,300 commits a second
  and holds 1,260-2,200 ghost records in 8 pages; how the estimate moves with its statistics kept
  is not known.
- **`FORCESEEK` holds at sixteen** (p99 19-39 ms), at a cost at four: the forced runs came after
  sixteen writers had left the most ghost records, with ScalarDB committing 639-881 a second
  against 372-716, so part of the difference is the order. On large reads it costs 3x
  (`seekCost`, above).

### Where SQL Server's estimate of the rows in flight comes from (laptop)

`-Dnativejdbc.bench.estimateDrift=true`: the estimated plan of the committed rows of the join (both
`NOT EXISTS` on the in-flight indexes) with the setup's kept statistics, after 0, 2 and 10
modifications a row of both metadata tables (every row in flight and back) and with 0, 2, 5, 10
and 50 rows in flight when it is compiled.

- **The estimate is the index's rows at the moment of compiling**: 1, 2, 5, 10 and 50 rows for
  0, 2, 5, 10 and 50 in flight, after any number of modifications; the filtered statistics show 0
  modifications throughout, kept or not. The setup's statistics cannot hold it.
- **So a plan compiled under heavy writing estimates rows in flight**, as EC2's at sixteen writers
  (4-6), and nested-loop plans then scan the index per row. With the statistics kept nothing
  recompiles it, so such a plan stays until it leaves the cache, where before a refresh could
  replace it; at four writers EC2's plans were compiled with at most one row in flight and seek.

### `FORCESEEK` while rows are in flight, on EC2 (run `20261024-1049`)

The driver of `sqlserver-seek-inflight`: as `20261023-2312`, and a table with rows in flight (the
router's count, at most once a second) read with `FORCESEEK` in the committed rows' `NOT EXISTS`;
SQL Server alone, the machines and mode of `20261024-0023` (whose two "on" columns are in
brackets); two runs, reads committed/s with the check on, mean ms a read:

| writers | on | (kept statistics) | (`FORCESEEK` always) |
|---|---|---|---|
| 0 | 1,066-1,084, 1.6 ms | (1,504-1,688, 1.0 ms) | |
| 4 | 420-511, 3.6-4.4 ms | (372-525) | (184-365) |
| 16 | **254-259**, 7.3-7.5 ms | (13-26, 74-145 ms) | (189-271, 7.1-9.9 ms) |

- **Sixteen writers, fixed**: the join costs SQL Server 2.6-2.7 ms and 1,090 logical reads, against
  69-120 ms and 10,000-19,600; p99 27-29 ms. Four writers keep their 420-511 (p99 11-16 ms), the
  forced seek's cost there gone with the order of `20261024-0023`.
- **No writers, slower all through, not from the change**: the split read costs SQL Server the same
  415 µs and 431 logical reads as before (438 µs), with no rows in flight no `FORCESEEK`; the
  commits, untouched by it, went from 0.28 to 0.50 ms and the check off fell as far (1,094-1,120
  against 1,523-1,631), so the extra half millisecond is the round trip of these machines. The
  router's counts ran 40 times in 10 s, 19 µs each.

### With `FORCESEEK`, do the kept statistics still pay? (laptop)

The driver of main with `FORCESEEK` while rows are in flight, the writers mode over the join with
the refreshes of `statsEveryMs=2000`, the setup's kept statistics against the same returned to
automatic updates before the writers (`-Dnativejdbc.bench.autoStats=true`, the teardown's `UPDATE
STATISTICS`); kept and automatic in turn, two runs each, reads committed/s with the check on (with it
off 29-49 at four and 0 at sixteen in every run):

| writers | kept | automatic |
|---|---|---|
| 1 | 182, 274 | **360, 404** |
| 4 | 32, 239 | 224, 238 |
| 16 | 124, 164 | 122, 129 |

- **Automatic statistics lose nothing at one and four writers**, and the check reads less: its
  pairs 75-80 logical reads at four against 479-1,444 kept. The kept run of 32 at four had the
  check at 42 ms (1,444 logical reads, 31 ms CPU), a plan that came once in two.
- **At sixteen they read 13% fewer** (125 against 144), within these runs' noise; the laptop ran
  loaded (load average 15). The setup's statistics were kept for EC2's four writers, before
  `FORCESEEK`, so EC2 decides.

On EC2 (run `20261024-1408`, the machines of `20261024-1049`, `-Dnativejdbc.bench.autoStats=alternate`:
kept on runs 1 and 3, automatic on 2 and 4), reads committed/s with the check on:

| writers | kept | automatic | the check off, all four |
|---|---|---|---|
| 1 | 1,022-1,043 | 993-1,092 | 909-1,018 |
| 4 | 51, 384 | 294, 195 | 111-174 |
| 16 | 199, 117 | 171, 214 | 0 |

- **No difference the kept statistics make**: four writers 218 kept against 245 automatic on
  average, sixteen 158 against 193, each arm's two runs further apart than the arms.
- **The kept statistics' bad check came again**: run 1's check at four writers took 29.6 ms (833
  logical reads, 18.6 ms CPU), as the laptop's first kept run; the read itself 1.6-3.0 ms. With
  automatic statistics no check is among the costly statements; run 4 had one read plan of 27 ms
  (3,038 logical reads), 53 runs of 3,908.
- **These machines ran faster than `20261024-1049`'s**: 1,615-1,955 reads/s without writers
  against 1,066-1,084, ScalarDB 750-1,041 commits/s at four writers against 438-452, so four and
  sixteen writers churn harder and read less than there, in both arms.

## Reads beside ScalarDB writers (stage 1, one table)

`scalar.db.native_jdbc.predicate_checks` (design 4.2, the predicate's check) off and on:
`-Dnativejdbc.bench.writers=true`. Four driver transactions at SNAPSHOT, each two grouped reads
`SELECT region, SUM(amount) FROM o WHERE region = ? GROUP BY region` by a random region, retried
on `40001`, for 10 s, alongside ScalarDB writers updating random rows of `o`. Committed
transactions per second, retries per commit, p50 / p99 ms of a commit retries included; first run,
on a quiet machine (writers 0 / 1 / 4 / 16):

| engine | committed/s off | committed/s on | retries per commit off | on | p99 ms off | on |
|---|---|---|---|---|---|---|
| PostgreSQL | 479* / 700 / 172 / 0 | 1,501* / 932 / 700 / 427 | 0 / 1.5 / 11 / - | 0 / 0 / 0.01 / 0.02 | 15 / 11 / 202 / - | 7 / 9 / 11 / 19 |
| MySQL | 109* / 640 / 87 / 0 | 189* / 883 / 594 / 449 | 0 / 2.0 / 27 / 2,083 | 0 / 0 / 0.01 / 0.02 | 57 / 17 / 413 / 6,765 | 53 / 10 / 13 / 17 |
| MariaDB | 1,416 / 780 / 187 / 4 | 1,404 / 979 / 580 / 337 | 0 / 1.5 / 11 / 386 | 0 / 0 / 0 / 0.02 | 6 / 11 / 192 / 4,932 | 6 / 11 / 13 / 22 |
| SQL Server | 974 / 451 / 66 / 0 | 974 / 624 / 400 / 237 | 0 / 1.5 / 13 / 2,247 | 0 / 0 / 0 / 0.02 | 7 / 24 / 824 / 10,102 | 9 / 15 / 20 / 36 |
| Oracle | 1,087 / 654 / 10 / 0 | 1,249 / 734 / 463 / 204 | 0 / 1.7 / 228 / - | 0 / 0 / 0.02 / 0.08 | 8 / 13 / 124 / - | 6 / 14 / 18 / 218 |

\* The first configuration measured after the setup, cold: with no writer both settings run the
same statements (no row in flight, the predicate's check never runs), as MariaDB and SQL Server
show. The second run adds a pass not reported for this, but ran on a machine under load from
other work (load average 20-27, every figure lower, ScalarDB's own commits included): there with no
writer off / on gave 449 / 473 (PostgreSQL), 842 / 805 (MySQL), 284 / 521 (MariaDB), 220 / 246 (SQL
Server), 844 / 1,033 (Oracle), and with writers the same picture as below.

- **Without writers, nothing changes**, by construction: the predicate's check runs only when the
  table's check finds a row, which is then cached as passed as before.
- **With writers, today's check starves the readers**: 1.5-2 retries per commit at one writer,
  11-228 at four, at sixteen no commit at all on three engines and 4 per second on MariaDB. With the
  predicate's check retries stay under 0.1 per commit at every count, and committed reads per
  second are 1.1-1.4x at one writer, 3-46x at four, and 204-449 against 0-4 at sixteen.
- **ScalarDB's throughput**: unchanged within noise on PostgreSQL, MySQL, MariaDB and SQL Server;
  on Oracle at sixteen writers 629 against 407 commits/s with the setting on (first run), and on
  PostgreSQL and Oracle lower in the loaded second run. The readers take no lock at SNAPSHOT, so
  this looked like the laptop's CPU shared with readers that now complete; on separate machines
  (below) the others held and Oracle 19c did not.

## Reads beside ScalarDB writers on AWS (2026-10-22)

The same mode on dedicated machines, run `20261022-0044` (`docs/plans/benchmark-environment.md`):
tier A (self-managed PostgreSQL and MariaDB on EC2, the databases capped at 1 GB of memory), tier B
(RDS: PostgreSQL, MySQL, MariaDB, Oracle 19c SE2), one engine at a time, two runs each; the range
is the two runs. Committed reads per second off → on, and ScalarDB's commits per second at
sixteen writers:

| engine | 1 writer | 4 writers | 16 writers | ScalarDB commits/s at 16, off → on |
|---|---|---|---|---|
| PostgreSQL A | 1,422-1,468 → 2,301-2,377 | 379-392 → 1,953-2,074 | 0-1 → 1,437-1,570 | 4,920-5,245 → 5,001-5,276 |
| PostgreSQL B | 1,412-1,426 → 2,376-2,504 | 61-90 → 2,098 | 0 → 1,681-1,692 | 2,073-2,085 → 2,085-2,103 |
| MariaDB A | 1,905-1,960 → 1,718-1,780 | 238-268 → 773-774 | 0 → 51 | 2,763-2,821 → 2,717-2,763 |
| MariaDB B | 1,066-1,111 → 1,307-1,339 | 116-120 → 803-808 | 0 → 399-401 | 1,300-1,306 → 1,463-1,473 |
| MySQL B | 1,138-1,140 → 1,516-1,530 | 130-191 → 1,127-1,144 | 0 → 796-808 | 1,802-1,815 → 1,548-1,593 |
| Oracle B (19c) | 209-211 → 30-40 | 0-2 → 12-13 | 0 → 10 | 1,617-1,690 → 1,193-1,194 |

- **PostgreSQL, MySQL and MariaDB: the laptop's picture holds.** With the setting on, retries stay
  at or below 0.02 per commit at every count; at four and sixteen writers committed reads are 3-35x,
  or hundreds per second against none. ScalarDB's own commits move within about 13%, both ways: the
  readers take no lock at SNAPSHOT.
- **Where it costs.** MariaDB A at one writer, 9% fewer reads: the check costs more there than the
  one retry per commit it saves. MariaDB A at sixteen writers only 51 per second against about 400 on
  tier B, p50 77 ms: the 1 GB cap is the suspect, not checked.
- **Oracle 19c on RDS: slower with the setting.** With rows in flight a read takes about 300 ms (p50
  at four writers 308 ms), so reads fall at one writer already, and ScalarDB's commits at sixteen
  writers by 28%, as on the laptop (629 → 407): readers using the database's CPU on slow checks.
  Oracle 23 on the laptop, with the same setup, gains as the others do (4 writers: 23 → 505, p50
  7 ms), so this is the instance: version, edition, storage or the data table's statistics, which
  RDS's nightly job gathers. It was the plan (below), since fixed.
- **Not measured.** Tier A's MySQL failed from the writers runs on (the cap is the suspect); SQL
  Server is not in the AWS plan. MySQL B's first configuration ran cold (158 against 1,997 with no
  writer, the same statements).

The diagnostic: `-Dnativejdbc.bench.checkPlan=true` (Oracle only) writes
`build/bench/inflight-plan-oracle.txt`: the server, the statistics and indexes of `o` and its
metadata table, and with 100 rows in flight held fixed, then beside 4 ScalarDB writers, the median
of the table-wide check, the predicate's check, each of its halves and the read, and the
predicate's check's plan as estimated and as run (`DBMS_XPLAN.DISPLAY_CURSOR`, `ALLSTATS LAST`).
On AWS, tier B's Oracle alone (`tier_a = []`, `tier_b = ["oracle"]`):
`WRITERS=1 READS=0 REPS=1 run.sh run -Dnativejdbc.bench.checkPlan=true`. On the laptop's Oracle 23
every statement takes 1-2 ms, the check driven by the `region` indexes of both images.

On RDS 19c (run 20261022-1707, 100,000 rows of `o`) the before images were the cost: 47 ms a check
beside 4 writers, the after images 0.8 ms. They joined the data table only for the key, and without
statistics on it 19c read all of its key index (`INDEX FAST FULL SCAN`, 100K rows) and probed the
metadata table's `before_region` index once per key, so the cost grew with the table, not with the
rows in flight. Read from the metadata table alone, which holds the key too, the before images
start from its in-flight index: 0.3 ms, the predicate's check 0.7 ms against the table-wide check's
0.3 ms (run 20261022-2018). The setting is on for Oracle again.

With that, the writers mode on both Oracles, run `20261022-2108`: tier A Oracle 23 Free on EC2
(`gvenzl/oracle-free:23-slim`, which the product caps at 2 CPUs and 2 GB), tier B RDS 19c SE2, two
runs each. Committed reads per second off → on, and ScalarDB's commits per second off → on:

| engine | writers | reads/s | p50 ms on | ScalarDB commits/s |
|---|---|---|---|---|
| Oracle B (19c) | 0 | 2,689-2,722 → 2,662-2,712 | 1.4 | - |
| | 1 | 1,321-1,370 → 1,691-1,746 | 2.3-2.6 | 160-172 → 166-170 |
| | 4 | 3 → 1,182-1,223 | 3.2-3.3 | 550-560 → 523-560 |
| | 16 | 0 → 848-876 | 4.3-4.4 | 1,725-1,756 → 1,711-1,737 |
| Oracle A (23 Free) | 0 | 2,062-2,073 → 2,034-2,047 | 1.9 | - |
| | 1 | 1,164-1,217 → 1,005-1,110 | 3.4-3.5 | 202-239 → 190-227 |
| | 4 | 11-58 → 657-739 | 4.1 | 786-865 → 614-625 |
| | 16 | 0 → 280-283 | 5.7-5.8 | 929-962 → 602-609 |

- **Oracle 19c: now as the others.** Reads gain from one writer on, ScalarDB's commits within 5%,
  retries at or below 0.13 per commit. This run's reads with no writer take 1.4 ms against the
  first run's 16 ms, from a setup the reports do not show, so compare off with on within a run.
- **Oracle 23 Free: ScalarDB pays.** Reads gain as on 19c, but ScalarDB's commits fall 28% at four
  writers and 35% at sixteen, and reads at one writer by 9-14%. Free's 2 CPUs are the suspect, not
  checked: reads that now commit use the CPU that aborted ones left to the writers, where the RDS
  instance's 8 vCPUs leave room for both. As MariaDB A at one writer, it is the cost on a database
  short of CPU.
