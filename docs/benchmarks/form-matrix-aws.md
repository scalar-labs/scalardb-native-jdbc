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

## Setup, to rerun

Everything below is what this run used; `bench-env/` builds it with `run.sh plan` and `run.sh run`
([the plan](../plans/benchmark-environment.md) has the account setup).

### Code

- The working tree as of commit `3a99c6e`, copied to the client by `run.sh`, **plus the uncommitted
  Oracle fixed statistics of todo 094** (`Setup.java`), which the DRIVER mode runs: Oracle's DRIVER
  rows measure that change. A rerun without it measures Oracle's gathered statistics instead.
- `bench-env/matrix.sh` with `REPS=1 INFLIGHT="100 10000"`, passing `-Dnativejdbc.bench.timeoutSeconds=30`;
  VIEW_CASE with its `COALESCE` filter (`-Dnativejdbc.bench.plainCaseFilter` unset).

```sh
cd bench-env
BENCH_DB_PASSWORD=$(cat secrets/db_password) SSH_KEY=$PWD/ssh/bench MAX_RUN_MINUTES=480 \
  REPS=1 INFLIGHT="100 10000" bash run.sh run -Dnativejdbc.bench.timeoutSeconds=30
```

with `terraform/bench.tfvars`: `tier_a = ["postgresql", "mysql", "mariadb"]`,
`tier_b = ["postgresql", "oracle", "mysql", "mariadb"]`, `region = "us-east-1"` (the default).

### Machines

All in one VPC, one subnet and one availability zone of us-east-1, the client in the same zone.

| Role | Type | vCPU | Memory | Storage | Software |
|---|---|---|---|---|---|
| Client, both tiers' JVMs | EC2 m6i.4xlarge | 16 | 64 GiB | 50 GB gp3 | Ubuntu 24.04, Temurin 25 JDK, Gradle wrapper |
| Tier A, one per engine | EC2 m6id.2xlarge | 8 | 32 GiB | data on the 474 GB instance-store NVMe (ext4) | Ubuntu 24.04, Docker, the official image, host networking |
| Tier B, one per engine | RDS db.m5.2xlarge, single-AZ | 8 | 32 GiB | 100 GB gp3 (3,000 IOPS, 125 MiB/s baseline) | default parameter group, no backups |

### Databases

| Engine | Tier A (EC2) | Tier B (RDS) |
|---|---|---|
| PostgreSQL | 16.15 (`postgres:16`): `shared_buffers=8GB`, `effective_cache_size=24GB`, `work_mem=64MB`, `maintenance_work_mem=1GB`, `random_page_cost=1.1`, `jit=on` | 16.13: `shared_buffers` 7.6 GB, `effective_cache_size` 15.2 GB, `work_mem=4MB`, `random_page_cost=4`, `jit=off` |
| Oracle | not run (needs a 23ai Enterprise Edition image) | SE2 19.32, licence included: `sga_target` 23 GB, `pga_aggregate_target` 3.8 GB, `cpu_count=8` |
| MySQL | 8.4.11 (`mysql:8.4`): `innodb_buffer_pool_size=20G`, `innodb_redo_log_capacity=4G` | 8.4.11: `innodb_buffer_pool_size` 22.5 GB |
| MariaDB | 11.4.13 (`mariadb:11.4`): `innodb_buffer_pool_size=20G`, `innodb_log_file_size=4G` | 11.4.13: defaults (this run's reports could not read MariaDB's settings; fixed since) |

Every report's first line records the version and these settings as the database reported them.
The PostgreSQL values in this run's reports are mis-printed: the setting and its unit are joined, so
`shared_buffers=10485768kB` is 1,048,576 blocks of 8 kB (8 GB). Fixed since, with `current_setting`.

### Data

Loaded by bulk `INSERT ... SELECT` before any read, not through ScalarDB, into the schema `bn`; each
ScalarDB table has a metadata table with one row per data row, all committed (`tx_state` 3), then a
fixed count set in flight.

| Table | Rows | Columns | In flight |
|---|---|---|---|
| `o` orders | 500,000 | `id` key, `customer_id` (5 per customer), `total` | the run's count (100 or 10,000), evenly spread |
| `c` customers | 100,000 | `id` key, `name`, `region` (1,000 regions) | the run's count, evenly spread |
| `w` wide | 100,000 | `id` key and 49 non-key columns | the run's count, at `c`'s ids |
| `k` composite key | 100,000 | `(pid, cid)` key, `grp`, `val` | the run's count, at `c`'s ids |
| `h` hot | 20,000 | `id` key, `qty`, `grp` (200 groups) | the run's count, from a 20,000-row table |
| `r` regions | 1,000 | `id` key, `label` | 10 (every 100th) |
| `p`, `d` | 10 each | plain tables, no metadata | none |

A row in flight is prepared (`tx_state` 1) with a before image equal to its current values, so every
form must return BASE's rows; the reports mark any difference with `!`. The driver's setup gathers
statistics with the rows in flight (`-Dnativejdbc.bench.gatherInflight` defaults to the run's count).

### Workload

- 41 queries (the report's rows) by 33 forms, each through the vendor JDBC driver except DRIVER,
  which goes through this driver.
- Per engine, one vendor connection and one driver connection, one query at a time: latency, not
  throughput. The seven engines ran
  side by side, each in its own test JVM on the client.
- Per query, each form in turn, through one prepared statement: a first run at a customer and
  region with rows in flight (the rows compared), up to 20 warm-up runs or 2 s, then up to 200 timed
  runs or 3 s with random customers and regions from a fixed seed. The report gives the median and
  p95 in ms and the ratio of the median to BASE's.
- A 30-second query timeout; a form that times out or fails is left out of the ranking.
- One repetition at each in-flight count: a gain under 2x may be noise.

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

### Strategy

The strategy depends on the isolation level, because the two levels face different problems.
Under READ COMMITTED a read never aborts: it returns the before image of a row in flight, and the
question is how fast it can do that. Under SNAPSHOT and SERIALIZABLE a read must abort when a row
in flight could change its result, and the question is how rarely. Neither part depends on the
engine, and the driver can choose from the statement alone.

#### READ COMMITTED: three read rules by query shape

| Query shape | Form | Gain over DRIVER, 1x and 10x data |
|---|---|---|
| Everything else: key lookups, filters, joins, subqueries, pages with an OFFSET, COUNT by FK, DISTINCT | Keep (the split read, SPLIT_DRV) | - |
| ORDER BY + LIMIT with no OFFSET, joined or not, keyset or range | VIEW_CASE, plain filter | 1.0x-33,000x |
| COUNT, SUM, GROUP BY over a whole table | CORR | 1.3x-11x; Oracle at 10,000 in flight 0.8x-1.0x |

What these rules give up, against the per-engine table below:

- CORR's DISTINCT on MariaDB (14x-77x) and PostgreSQL at 10x data (1.7x-9.1x).
- OVER for Oracle's deep OFFSET (3.2x-13x at 1x data).
- Up to 20% on Oracle's whole-table aggregates at 10,000 in flight.

If MariaDB matters, a DISTINCT rule for it is the one engine-specific exception worth adding.

#### SNAPSHOT and SERIALIZABLE: when to abort

Today (design 4.2), a read whose rows reach the application directly gets an exact check: it
aborts only on a row in flight that the WHERE clause matches by its after or before image. Every
other shape takes the table-wide check, which aborts on a row in flight anywhere in the table:

- grouped, aggregate and DISTINCT reads;
- OFFSET and scrollable result sets;
- joins and subqueries past the row-by-row detector.

Under ScalarDB writers that starves those reads. With 4 writers on RDS PostgreSQL, grouped reads
committed 90/s at 72 retries per commit. On a join, 44-53% of reads abort at one writer and
92-100% at sixteen. The strategy narrows the table-wide check in three tiers:

| Shape | Check | Status |
|---|---|---|
| One table, pure WHERE clause | **Predicate check**: the WHERE clause over the in-flight rows' before and after images; abort only on a match | Built (`scalardbnative.predicateChecks`, `predicate-checks` branch), measured on AWS |
| Joins and semi-joined subqueries the split read covers | **In-flight pairs**: the statement as `SELECT 1` over every combination with at least one table's row in flight, by its before or after image, and the data tables for the rest | Designed and measured as SQL; not built |
| Scalar subqueries, CTEs, past the copy limit | Table-wide check, as today | - |

Both narrowed checks run only when the cheap table-wide probe finds a row in flight. Then they
cost one indexed statement: 0.5-2 ms for the predicate check and 0.4-2.4 ms for in-flight pairs.

**Measured** (the reports are `bench-env/results/20261022-0044/*/writers-*.md` and
`20261022-2108`; the method is in [the in-flight check benchmark](inflight-check.md) on
`predicate-checks`):

- **Predicate check, reads committed/s, check off → on:**
  - RDS PostgreSQL at 4 writers: 90 → 2,098.
  - RDS Oracle 19c: 3 → about 1,200 at 4 writers, 0 → about 860 at 16.
  - PostgreSQL on EC2 and RDS, MySQL on RDS, MariaDB on EC2 and RDS: large gains.

  ScalarDB's own commits stay within 5-13%. Oracle 19c's check took about 300 ms until eef0e86,
  because it joined the data table for the before images; it now reads them from the metadata
  table alone, 47 → 0.7 ms.
- **In-flight pairs, joins:** aborts fall to 0-3% at 1, 4 and 16 writers on all five engines,
  about the single-table check's level. Checking each table against only its own conditions does
  not work: a lookup table usually has none, is checked whole, and still aborts 64-98% at four
  writers.

**Limits:**

- **SERIALIZABLE on MySQL, MariaDB and SQL Server keeps the table-wide check.** Its locking read is
  what holds off later prepares, and a predicate would lock the same range.
- **Writers pay on CPU-bound hosts.** Reads that now succeed compete with ScalarDB's writers. On
  Oracle 23 Free (2 CPUs) ScalarDB's commits fell 28-35%; on MariaDB on EC2, reads fell 9% at one
  writer. This is a product tradeoff, more reads that finish against writer throughput, and is
  likely Free's CPU cap (not confirmed).
- **SQL Server:** not measured on AWS. Its in-flight pairs check took 16-18 ms because it reuses a
  plan cached while nothing was in flight; this needs solving before SQL Server takes that form.
- **MySQL on tier A:** the writers runs failed there. MySQL's first configuration after the
  warm-up also measures cold (158/s with the check off against 1,997/s on with no writers), which
  is the benchmark, not the check.

**Default:** keep the predicate check experimental and off by default until SQL Server and tier A
MySQL are measured, then make it the default. Build in-flight pairs next, as stage 2.

**Next to measure: the plain query after a passed check.** If the check passes, no row in flight
matches the read by either image, so the plain query on the data tables returns exactly the
committed rows in that snapshot. The driver could run it (BASE) instead of the split read, which
is 1.1x to 1,000x behind BASE in these runs. This holds for one table after a predicate check,
and for joins after in-flight pairs. It needs these conditions:

- **One snapshot.** The check and the plain query must run in the same snapshot. Inside a
  transaction they do. In autocommit they must still share one transaction: as two autocommit
  statements, a row can go in flight between them.
- **The user's columns only.** In the inline layout the data table also holds ScalarDB's metadata
  columns, so `SELECT *` or `t.*` against it would return them too. The plain query must name the
  user's columns, or the rule is limited to decoupled tables.
- **ScalarDB's layout.** The data table holds the committed image of every row not in flight. That
  covers prepared updates, inserts (whose after image is in the table) and deletes (tx_state 2).
  Nothing else is needed.

CORR does not combine with the checks. Its whole-table aggregates have no WHERE clause, so any row
in flight fails the check and the read aborts. Correcting instead of aborting would be a design
change and needs its own write-up first.

### Per engine

Which form to read each kind of query with, per engine. Keep means the split read the driver emits
today (SPLIT_DRV, `SplitRead.java`): the committed copy excludes rows in flight by `NOT EXISTS` on
PostgreSQL and Oracle, and by a left join to the DISTINCT in-flight keys on MySQL and MariaDB.
Brackets give the gain over DRIVER measured here.

| Query type | PostgreSQL | Oracle | MySQL | MariaDB |
|---|---|---|---|---|
| Key lookups, filters, FK filters, IN / NOT EXISTS subqueries, LEFT JOIN, 2-4 table joins | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, DISTINCT set) | Keep (SPLIT_DRV, DISTINCT set) |
| Paging: ORDER BY key + LIMIT, keyset, indexed range + LIMIT | VIEW_CASE (1.7x-14x) | VIEW_CASE (10x-37x) | VIEW_CASE (2x-111x) | VIEW_CASE (1.5x-11x) |
| Paged join, 2-3 tables | VIEW_CASE, plain filter (4.9x-1,400x) | VIEW_CASE (4x-163x) | VIEW_CASE (8x-2,800x) | VIEW_CASE (530x-1,200x) |
| Deep OFFSET page | Keep (SPLIT_DRV, NOT EXISTS) | OVER (3.2x-13x) | VIEW_CASE (3.5x-13x) | VIEW_CASE (3.7x-4.5x) |
| Full-scan COUNT, SUM, GROUP BY | CORR (1.4x-11x) | CORR for COUNT (1.1x-2.1x); Keep (SPLIT_DRV, NOT EXISTS) for SUM and GROUP BY | CORR (1.5x-2.8x) | CORR (1.1x-2.6x) |
| DISTINCT | SPLIT_NI or SPLIT_DHDP (1.5x-2.3x) | Keep (SPLIT_DRV, NOT EXISTS) | Keep (SPLIT_DRV, DISTINCT set); no form helps | CORR (1.5x-14x) |
| Wide tables, 4-table key lookup | VIEW_CASE (3.2x-3.5x) | Keep (SPLIT_DRV, NOT EXISTS) | VIEW_CASE (1.7x-2.7x) | VIEW_LJ (2.3x-3.1x) |

The first run's VIEW_CASE used the `COALESCE` filter, not the plain one the driver emits; the
second run (below) used the plain one, which settles PostgreSQL's paged joins on VIEW_CASE. The
CORR and OVER cells and that one come from the second run, three repetitions each. Forms that beat DRIVER by up to 1.5x on joins at 100 rows
in flight lose to it at 10,000, and the driver cannot know the count when it plans, so those stay Keep.

## The approaches

Every form reads the same thing: each ScalarDB table as of its last commit. A row is either
committed (`tx_state` 3, or no metadata row), whose data row holds its committed values, or in
flight (`tx_state` 1 prepared or 2 deleted), whose data row holds the after image and whose
metadata row holds the before image, null for a row inserted in flight. The forms differ in where
the choice between the two is made, and that decides which plans the engine can still find.

### The forms measured here

| Form | What it is |
|---|---|
| BASE | The query on the data tables, ignoring the metadata: the target, and wrong whenever a row is in flight. |
| VIEW_LJ, VIEW_AJ | Through a view per table: a UNION ALL of the committed rows (a left join on the metadata, or an anti-join on the rows in flight) and the before images of the rows in flight. |
| VIEW_CASE | Through a view per table without a UNION: one left join on the metadata, each column `CASE WHEN in flight THEN before ELSE data END`. Keeps the data table's index order, hence its strength on pages. |
| SPLIT_LJ, _AJ, _NI, _HJ, _HD | The split read: the query repeated once per combination of its tables' copies (committed or before image), joined by UNION ALL; 2^n copies for n tables. The suffix is how the committed copy drops rows in flight: left join on the metadata (LJ), `NOT EXISTS` (AJ), `NOT IN` (NI), a left join to the set of keys in flight (HJ), or to that set made DISTINCT (HD). |
| SPLIT_PA, _PH | The split read with the aggregate computed in each copy and the copies' results combined on top. |
| SPLIT_LT, _LH | One copy per table in flight, the others joined through LATERAL (CROSS APPLY), either image. |
| SPLIT_MX, _MH | The all-committed copy by anti-join (or DISTINCT set), the other copies by left join on the metadata. |
| SPLIT_D* | The same forms with each table replaced by a derived table of its copy's rows, so the rest of the statement is left as written; SPLIT_DRV is the driver's choice per engine. |
| SPLIT_DRVP, _DAJP, _DHDP, _DLJP, _DRVR | The derived forms with the ORDER BY and limit (or DISTINCT) also applied inside each copy; DRVR uses `ROWNUM` on Oracle. |
| SPLIT_DRVN | SPLIT_DRV with each derived table selecting only the columns the query uses. |
| SPLIT_DAJN, _DAJH | MySQL: the anti-join kept a per-row probe (`NO_SEMIJOIN`). |
| DRIVER | The statement through this driver, as it rewrites it today. |
| BASE_OPEN | BASE with the rows in flight held by an open native transaction: what the database alone pays. |

### New approaches for the queries no form fixes

The forms above all rewrite the whole query, so the engine plans an anti-join or a derived table
where the plain query had an index-order scan with an early stop, a loose index scan or a semijoin.
The three approaches below keep the plain query's plan for the committed rows and pay for the rows
in flight separately, in work that grows with how many are in flight rather than with the table.
They are modes CORR, OVER and SEMI of `ReadOverheadBenchmark`, written out per query.

**A. Correct the plain aggregate (CORR).** For a COUNT or SUM, the committed answer is the plain
answer over the data table, minus what the rows in flight contribute through their after images,
plus what they contribute through their before images. All three in one statement, so one snapshot:

```sql
SELECT SUM(v.n) FROM (
  SELECT COUNT(*) AS n FROM o b WHERE b.customer_id = ?          -- plain
  UNION ALL SELECT -COUNT(*) FROM o f JOIN om fm ON fm.id = f.id   -- after images in flight
    WHERE fm.tx_state IN (1, 2) AND f.customer_id = ?
  UNION ALL SELECT COUNT(*) FROM o e JOIN om em ON em.id = e.id    -- their before images
    WHERE em.tx_state IN (1, 2) AND em.before_tx_id IS NOT NULL AND em.before_customer_id = ?) v
```

GROUP BY sums the three per group and drops groups that reach zero. DISTINCT keeps the plain
DISTINCT (a loose index scan), drops a value only rows in flight carry and no committed row has,
and adds the before images' values. Exact for COUNT, SUM and DISTINCT; MIN, MAX and AVG over
averages are not sums and stay on the split read. A SUM whose rows are all inserted in flight gives
0 where NULL is due, so the driver adds the count of the summed values to tell.

**B. Over-fetch the page (OVER).** For `ORDER BY ... LIMIT n` over one table or a join, run the plain
query with the limit raised by a margin, drop each result row that holds a row in flight, and add
the split read's copies that hold one, each with the ORDER BY and limit inside it; take the page
from both:

```sql
SELECT v.aid, v.bid FROM (
  SELECT w.* FROM (SELECT a.id AS aid, b.id AS bid FROM c a JOIN o b ON b.customer_id = a.id
                   ORDER BY b.id DESC LIMIT 120) w                 -- plain, margin 100
    WHERE NOT EXISTS (SELECT 1 FROM cm WHERE cm.id = w.aid AND cm.tx_state IN (1, 2))
      AND NOT EXISTS (SELECT 1 FROM om WHERE om.id = w.bid AND om.tx_state IN (1, 2))
  UNION ALL SELECT * FROM (<copy with a in flight> ORDER BY b.id DESC LIMIT 20) p1
  UNION ALL SELECT * FROM (<copy with b in flight> ...) p2
  UNION ALL SELECT * FROM (<copy with both in flight> ...) p3) v
ORDER BY v.bid DESC LIMIT 20
```

It is exact when the window keeps at least n rows (offset + n with an OFFSET) or holds the whole
result: the kept rows are the committed ones in order, so a committed row outside the window ranks
below all of them. Otherwise the driver reads again with a larger margin; this benchmark's margin
(100 rows, 1,000 past an offset of 10,000) always holds. The window needs each table's key in its
select list, which the driver adds as it does for versions. One table is the same with one copy,
which is why the single-table pages are under B, not A.

**C. Split only the subquery (SEMI).** An `IN` or `NOT EXISTS` over a ScalarDB table becomes one
predicate on the table's committed rows and one on its before images, each on the table itself
rather than on a view or derived table, so the engine keeps its semijoin or antijoin:

```sql
-- a.id IN (SELECT customer_id FROM o), committed
SELECT a.id FROM c a WHERE <a committed> AND a.region = ?
  AND a.id IN (SELECT b.customer_id FROM o b WHERE <b committed>)
UNION ALL SELECT a.id FROM c a WHERE <a committed> AND a.region = ?
  AND a.id IN (SELECT fm.before_customer_id FROM o f JOIN om fm ON ... WHERE <f in flight>)
  AND NOT EXISTS (SELECT 1 FROM o g WHERE <g committed> AND g.customer_id = a.id)
-- and the same two for a's before images
```

`NOT EXISTS` is the AND of two `NOT EXISTS`, one per image. The second IN arm excludes what the
first returned, so no row comes twice without a DISTINCT.

**Checking them.** With `-Dnativejdbc.bench.changed=true` the rows in flight get after images apart
from their before images, half of them become deletes in flight, and ten rows per table are inserted
in flight, at the first run's customer and region. BASE then reads wrong rows by design, so every
form's rows are compared with VIEW_LJ's instead. A form that confuses the two images, or keeps an
insert in flight, shows `!`.

### Local results of A, B and C

Measured on local containers (PostgreSQL 16, MySQL 8.4, MariaDB 11.4, Oracle 26ai Free) on one
laptop, the AWS data at 100 and 10,000 rows in flight, the modes BASE, VIEW_LJ, VIEW_CASE,
SPLIT_DRVP, SPLIT_DRVN, CORR, OVER, SEMI and DRIVER, `plainCaseFilter=true`, one run each. Absolute
times are not comparable with AWS; the ratios between forms are. The 10,000 set ran after baabc26
changed the views' before-image columns, which the VIEW_* forms read and the new forms do not.

**Correct everywhere.** With changed images, CORR, OVER and SEMI return VIEW_LJ's rows for every
query on all four engines at both counts. The only `error` cells are SPLIT_DRVN on the keyset and
3-table pages (as on AWS) and VIEW_CASE on NOT EXISTS on MariaDB and Oracle, which runs 0.5 to 2.6 s
elsewhere and most likely hit the 60 s timeout once the images changed.

Median ms, the new form against DRIVER and the best existing form (other than BASE):

| Query | PostgreSQL 100 | MySQL 100 | MariaDB 100 | Oracle 100 | PostgreSQL 10k | MySQL 10k | MariaDB 10k | Oracle 10k |
|---|---|---|---|---|---|---|---|---|
| A: COUNT of 100k | **3.2** / 7.7 / 5.9 | **7.7** / 10.3 | **8.4** / 18.1 | **1.9** / 4.6 | 13.4 / 19.0 / 13.2 | **27.8** / 54.0 | **26.4** / 33.4 | **8.3** / 11.4 |
| A: SUM of 500k | **12.4** / 37.2 / 17.1 | **44.6** / 63.2 | **42.4** / 92.9 | **12.0** / 16.7 | 35.4 / 58.8 / **27.9** | **77.2** / 196.3 | **67.5** / 120.2 | 36.9 / **34.2** |
| A: GROUP BY of 100k | **9.5** / 12.8 / 12.6 | **12.0** / 24.7 | **9.4** / 18.8 | 10.4 / **9.4** | **14.1** / 24.5 / 19.3 | **34.3** / 61.0 | **28.5** / 35.7 | **17.8** / 18.2 |
| A: hot COUNT of 20k | **1.1** / 2.0 / 2.0 | **2.0** / 2.5 | **2.2** / 4.0 | **1.0** / 2.3 / 2.1 | 6.3 / 6.1 / **4.8** | 16.9 / 22.9 / **15.3** | 15.2 / 14.7 / **14.6** | 6.6 / 7.8 / **3.4** |
| A: COUNT by FK | 0.52 / **0.47** | 0.71 / **0.65** | 0.66 / **0.63** | 0.91 / **0.63** | 1.35 / **0.60** | 11.3 / **1.8** | 10.0 / **0.73** | 3.6 / **0.62** |
| A: DISTINCT | 18.1 / 12.1 / **11.6** | 334 / 21.4 / **19.3** | **3.1** / 33.0 / 26.4 | 147 / 14.1 / **10.3** | 36.7 / 23.0 / **18.5** | 334 / 64.4 / **61.3** | **24.2** / 49.6 / 40.4 | 159 / 20.9 / **17.1** |
| B: page of 500k | **0.47** / 0.54 / 0.51 | 0.87 / 0.76 / **0.53** | 0.81 / 0.73 / **0.52** | 2.1 / 1.9 / **1.5** | 0.60 / 0.48 / **0.44** | 21.2 / 21.4 / **0.59** | 1.0 / 3.1 / **0.60** | 18.6 / 1.6 / **1.5** |
| B: page after 10k | 3.8 / 2.8 / **2.8** | 59.0 / 112.8 / **8.3** | 8.3 / 7.2 / **6.1** | **6.8** / 64.3 | 19.1 / 17.0 / **14.1** | 32.6 / 34.5 / **9.5** | 28.8 / 39.2 / **8.6** | **31.6** / 82.6 |
| B: JOIN FK page | 1.2 / 1.1 / **0.53** | 54.7 / 2.8 / **0.62** | 125 / 198 / **0.56** | 4.1 / 3.5 / **1.5** | 15.7 / 99.0 / **0.50** | 146 / 126 / **0.71** | 262 / 334 / **0.65** | 77.1 / 68.8 / **1.5** |
| B: keyset page | 0.52 / 0.53 / **0.51** | 1.5 / 1.7 / **1.2** | 0.82 / 0.85 / **0.65** | 1.7 / 11.3 / **1.4** | 3.4 / 3.4 / **0.66** | 3.9 / 20.0 / **1.4** | 2.0 / 1.1 / **0.64** | 3.4 / 11.9 / **1.6** |
| B: 3-table page | 4.3 / 4.0 / **0.56** | 71.1 / 781 / **0.66** | 137 / 207 / **0.66** | 84.4 / 16.9 / **1.6** | 1126 / 201 / **0.65** | 225 / 172 / **0.71** | 342 / 413 / **0.76** | 180 / 96.4 / **1.5** |
| B: indexed range | 0.51 / 0.72 / **0.47** | 0.95 / 0.82 / **0.57** | 0.81 / 1.0 / **0.56** | 5.3 / 1.9 / **1.4** | 0.89 / 0.79 / **0.48** | 10.4 / 5.7 / **3.0** | 0.89 / 0.80 / **0.61** | 9.1 / 5.3 / **1.4** |
| C: IN selecting a non-key | 4.3 / **0.85** | 2.0 / **1.7** | 2.0 / **1.5** | 10.3 / **5.3** | 24.6 / **15.8** | 42.5 / **24.5** | 275 / **6.4** | 136 / **5.7** |
| C: NOT EXISTS | 0.65 / **0.64** | **1.1** / 9.0 | 1.3 / **1.0** | 4.8 / **1.7** | 5.0 / **1.4** | 8.9 / **1.8** | 7.9 / **1.8** | 4.8 / **2.3** |

Each cell is new form / DRIVER / best existing form, the last left out where DRIVER is the best;
the fastest is in bold.

- **A wins the full-table aggregates.** At 100 in flight CORR runs within 1.2x of BASE on every
  engine (1.35x for Oracle's GROUP BY, 1.9x for its hot COUNT), 1.3 to 3x faster than DRIVER except
  on Oracle's GROUP BY. At 10,000 it is still the fastest full scan on MySQL and MariaDB and on most
  of PostgreSQL and Oracle's. Where it isn't, it is within 30% of SPLIT_DRVN or DRIVER. The hot COUNT
  at 10,000 goes to VIEW_CASE, by 4 to 30%, and by 2x on Oracle. It loses where the plain query is already an index lookup (COUNT by
  FK), and its DISTINCT works only on MariaDB. On MySQL and Oracle its committed probe per value costs
  more than the split read (147 to 334 ms against 10 to 60).
- **B is not worth it except for deep offsets on Oracle.** VIEW_CASE matches or beats it on every other
  page, and at 10,000 in flight its anti-join on the window and the 2^n − 1 copies cost more than they save
  (up to 1.1 s for the 3-table page on PostgreSQL). On Oracle, the page after 10k rows is the
  one where no existing form is fast: OVER takes 6.8 ms against DRIVER's 64 (31.6 against 82.6
  at 10,000).
- **C does not pay.** SEMI loses to DRIVER everywhere except MySQL's NOT EXISTS at 100 in flight
  (1.1 against 9.0 ms), and loses by 2 to 40x at 10,000.

So locally CORR is worth taking for COUNT, SUM and GROUP BY over a whole table on all four
engines, and for DISTINCT on MariaDB; OVER for an ORDER BY past a deep OFFSET on Oracle; SEMI for
nothing. The AWS run below confirms them.

### AWS results of A and B

A second run on the same AWS setup, 2026-10-21 16:01 to 16:48 (reports in
`bench-env/results/20261021-1601/`): the modes BASE, VIEW_LJ, VIEW_CASE, SPLIT_DRVP, SPLIT_DRVN,
CORR, OVER and DRIVER on the 12 aggregate, DISTINCT and paging queries, `plainCaseFilter=true`, 100
and 10,000 rows in flight, three timed repetitions and one run with changed images each. The
repetitions agree within a few percent; the figures are their medians. The working tree it ran
from held another session's uncommitted `predicateChecks` property, off by default.

**Correct everywhere.** With changed images, CORR and OVER return VIEW_LJ's rows on all seven
databases at both counts. The only errors are SPLIT_DRVN's on the keyset and 3-table pages.

Median ms, CORR or OVER / DRIVER / the best other form where DRIVER is not it:

| Query | Tier | PostgreSQL 100 | MySQL 100 | MariaDB 100 | Oracle 100 | PostgreSQL 10k | MySQL 10k | MariaDB 10k | Oracle 10k |
|---|---|---|---|---|---|---|---|---|---|
| A: COUNT of 100k | A | **3.7** / 11.9 / 7.2 | **8.7** / 13.3 | **11.1** / 22.4 | | **14.3** / 24.7 / 14.3 | **38.9** / 72.6 | **38.6** / 47.8 | |
| | B | **4.5** / 12.5 / 8.2 | **15.4** / 22.8 | **15.1** / 39.9 | **3.2** / 6.6 | **16.2** / 28.2 / 16.3 | **62.8** / 108.6 | **52.7** / 60.6 | **15.4** / 17.6 |
| A: SUM of 500k | A | **15.3** / 169.4 / 21.7 | **57.1** / 91.7 | **63.6** / 128.8 | | 40.4 / 182.5 / **29.7** | **100.1** / 277.9 | **99.0** / 164.3 | |
| | B | **18.8** / 62.1 / 24.8 | **100.0** / 152.2 | **89.7** / 217.5 | **18.2** / 27.0 | 48.3 / 91.5 / **36.5** | **163.7** / 416.8 | **135.1** / 211.3 | 49.9 / **41.6** |
| A: GROUP BY of 100k | A | **11.7** / 38.6 / 38.5 | **12.6** / 31.4 | **12.6** / 24.1 | | **15.9** / 52.7 / 45.4 | **45.9** / 82.0 | **44.4** / 50.9 | |
| | B | **14.7** / 20.8 / 20.6 | **20.5** / 55.2 / 52.9 | **17.1** / 42.5 | 12.7 / **11.4** | **18.4** / 37.0 / 27.9 | **73.9** / 118.3 | **57.0** / 64.4 | 25.0 / **22.7** |
| A: hot COUNT of 20k | A | **0.94** / 2.6 / 2.5 | **2.3** / 3.4 | **2.6** / 4.6 | | 8.3 / 8.2 / **5.4** | 24.7 / 32.3 / **22.0** | 22.8 / 23.0 / **21.1** | |
| | B | **1.3** / 2.9 / 2.8 | **3.7** / 5.2 | **3.6** / 8.3 | **1.4** / 3.7 / 2.9 | 9.3 / 9.2 / **7.1** | 39.4 / 47.6 / **34.3** | 31.3 / **28.3** | 10.9 / 10.3 / **4.6** |
| A: COUNT by FK | A | 0.12 / 0.14 / **0.11** | 0.57 / **0.53** | 0.44 / **0.37** | | 1.05 / **0.14** | 15.1 / **0.49** | 13.3 / **0.34** | |
| | B | 0.36 / 0.37 / **0.35** | 0.74 / **0.63** | 0.55 / **0.50** | 0.48 / **0.32** | 1.6 / **0.37** | 21.5 / **0.54** | 18.4 / **0.40** | 3.4 / **0.32** |
| A: DISTINCT | A | 48.0 / 39.2 / **37.7** | 546 / 28.6 / **24.5** | **4.3** / 40.8 / 32.6 | | **32.9** / 49.8 / 41.7 | 519 / 89.6 / **85.4** | **38.1** / 66.5 / 56.2 | |
| | B | 31.6 / 19.8 / **19.2** | 1016 / 46.3 / **40.4** | **5.4** / 77.5 / 40.2 | 199 / 13.1 / **10.4** | 59.5 / 35.4 / **26.1** | 958 / 134.3 / **125.9** | **58.0** / 87.3 / 70.5 | 218 / 25.0 / **22.7** |
| B: page after 10k | A | 4.9 / 3.2 / **3.2** | 68.1 / 163.3 / **13.4** | 7.1 / 7.2 / **6.6** | | **7.3** / 19.0 / 13.7 | 47.3 / 48.0 / **13.4** | 37.2 / 52.7 / **12.1** | |
| | B | 5.9 / 4.2 / **4.1** | 120.7 / 276.8 / **21.5** | **9.3** / 9.4 | **9.1** / 122.6 | 28.1 / 24.9 / **16.7** | 73.6 / 74.9 / **21.4** | 46.7 / 63.1 / **20.9** | **48.5** / 157.5 |

The fastest is in bold. On the other pages (of 500k, JOIN FK, keyset, 3 tables, indexed range)
VIEW_CASE is within 2.2x of BASE on every database (and far ahead of it on MariaDB's joins) and OVER is
1.2x to 2,500x behind it, so they are
left out.

- **A holds up on AWS.** CORR is within 1.12x of BASE on COUNT, SUM and GROUP BY over a whole table at
  100 in flight on every database but Oracle's GROUP BY (1.5x), and 1.4x to 11x faster than DRIVER
  (PostgreSQL's SUM on EC2: 15 ms against 169). At 10,000 it still beats DRIVER everywhere except
  Oracle's SUM and GROUP BY (by 10 to 20%), and loses only PostgreSQL's SUM to SPLIT_DRVN (1.4x).
  Taken for the whole category, its worst case is that 1.4x and its best case 11x, so the cell is
  CORR on PostgreSQL, MySQL and MariaDB, and CORR for COUNT only on Oracle. The cached hot COUNT is
  the exception: at 10,000 in flight VIEW_CASE or DRIVER takes it by 8 to 140%; the driver can't tell it
  from a full scan, and at 100 in flight CORR is 1.4x to 2.7x the faster.
- **CORR's DISTINCT is for MariaDB only:** 4.3 ms against 40.8 at 100 in flight (14x on RDS), 1.5x
  to 1.7x at 10,000. On MySQL and Oracle it is 6x to 22x slower than DRIVER; on PostgreSQL it wins
  only on EC2 at 10,000.
- **B only for Oracle's deep OFFSET:** 9.1 ms against DRIVER's 123 at 100 in flight, 48 against 157
  at 10,000, where no other form comes close. Elsewhere VIEW_CASE or DRIVER is faster.
- **VIEW_CASE with the plain filter settles PostgreSQL's paged joins:** 0.15 to 0.58 ms against
  DRIVER's 1.1 to 629 (4.9x to 1,400x), so the open cell is VIEW_CASE.

### AWS results with 10x the data

A third run, 2026-10-21 15:45 to 17:26 UTC, with no laptop attached (reports in
`bench-env/results/20261022-0044/`). It ran the modes BASE, VIEW_LJ, VIEW_CASE, CORR and DRIVER on
the same 12 queries, with these settings:

- `scale=10`: `c` has 1M rows and `o` has 5M. The query names still say 100k and 500k.
- `inflightAt=newest`: the rows in flight are at the highest ids, where the pages read.
- Tier A's databases are capped at 1 GB: `docker --memory=1024m`, `shared_buffers` 256 MB and an
  InnoDB pool of 384 MB. RDS is not capped.
- A 60 s timeout per statement.
- Two timed repetitions and one run with changed images, at 100 and 10,000 in flight.

**Correct wherever it could be checked.** With changed images, CORR, VIEW_CASE and DRIVER return
VIEW_LJ's rows on all seven databases. Two things are unchecked or missing:

- **MariaDB's two paged joins are unchecked.** VIEW_LJ timed out on them, which left nothing to
  compare with. The reports show `!` there; since 28c51d9 that case shows `?`.
- **Tier A's MySQL failed from 16:54 on:** the changed run at 10,000 and the writers runs. The log
  names only the failed task. The 1 GB cap is the likely cause, but this is not verified.

CORR's DISTINCT also gave `error` on MySQL at 100 in flight on both tiers.

Median ms, CORR or VIEW_CASE against DRIVER (DRIVER / form, so above 1x the form wins), tier B,
100 / 10,000 in flight; tier A gives the same picture:

| Query | Form | PostgreSQL | MySQL | MariaDB | Oracle |
|---|---|---|---|---|---|
| COUNT of 1M | CORR | 7.3x / 2.3x | 1.5x / 3.0x | 1.9x / 1.7x | 2.2x / 0.8x |
| SUM of 5M | CORR | 5.6x / 2.3x | 1.5x / 3.1x | 1.8x / 1.8x | 2.5x / 1.0x |
| GROUP BY of 1M | CORR | 4.1x / 2.4x | 3.2x / 2.7x | 1.9x / 1.6x | 1.3x / 0.8x |
| hot COUNT of 20k | CORR | 2.2x / 1.0x | 1.5x / 1.2x | 1.8x / 0.9x | 2.7x / 1.0x |
| COUNT by FK | CORR | 0.6x / 0.2x | 0.9x / 0.0x | 0.9x / 0.0x | 0.7x / 0.0x |
| DISTINCT | CORR | 4.2x / 1.7x | error / 0.1x | 77x / 14x | 0.2x / 0.0x |
| page of 5M, no offset | VIEW_CASE | 1.8x / 48x | 3.6x / 189x | 2.2x / 19x | 451x / 538x |
| page after 10k | VIEW_CASE | 0.3x / 1.3x | 125x / 3.5x | 0.5x / 3.7x | 12x / 14x |
| page: JOIN FK | VIEW_CASE | 4.7x / 563x | 13x / 719x | 17,700x / 20,500x | 3.3x / 657x |
| page: keyset | VIEW_CASE | 1.1x / 14x | 1.3x / 18x | 1.5x / 1.4x | 1.0x / 1.0x |
| page: 3 tables | VIEW_CASE | 105x / 2,200x | 14,700x / 33,200x | 15,800x / 18,700x | 290x / 1,460x |
| page: indexed range | VIEW_CASE | 2.6x / 20x | 1.7x / 45x | 1.6x / 22x | 1.0x / 11x |

- **The paging rule holds at 10x.** VIEW_CASE ties or beats DRIVER on every page without an
  OFFSET, on every database at both counts. There is one exception: PostgreSQL's keyset page on
  EC2 at 100 in flight, 0.51 ms against 0.35. With rows in flight at the newest ids, DRIVER's
  pages fall 10x to 1,000x behind at 10,000. VIEW_CASE stays within 1.1x to 2.4x of BASE, except
  the 3-table page on EC2 (28.7 ms, the same as BASE).
- **MariaDB's joins are slow even without the driver.** Its plain paged joins take 5 to 9 s, and
  VIEW_CASE takes 0.4 to 0.6 ms.
- **Deep OFFSET is mixed.** At 100 in flight VIEW_CASE loses to DRIVER on PostgreSQL and MariaDB
  (0.3x to 0.6x) and wins on MySQL and Oracle. At 10,000 it wins everywhere (1.3x to 14x). So
  pages with an OFFSET stay with the split read.
- **CORR holds for full-table COUNT, SUM and GROUP BY**, within 1.05x of BASE at 100 in flight. It
  is 1.3x to 7.3x faster than DRIVER at 100 in flight and 1.4x to 5.4x at 10,000. Oracle at
  10,000 is the exception: there it is 0.8x to 1.0x.
- **CORR's DISTINCT now also wins on PostgreSQL** (1.7x to 9.1x). It stays far ahead on MariaDB
  and loses badly on MySQL and Oracle.
- **CORR loses where it lost before:** COUNT by FK, which the driver already reads by index. On
  the cached hot COUNT at 10,000 it is about even.
- **VIEW_CASE never helps an aggregate.** It is 1.4x to 10x slower than DRIVER on every full scan.

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
