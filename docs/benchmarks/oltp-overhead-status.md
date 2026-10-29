# OLTP overhead: where it stands (2026-10-26, evening)

**Note, 2026-10-29.** A driver connection starts at SNAPSHOT since 2026-10-29; this page's "default
level" wording, READ COMMITTED, is that of the day it was written.

**Update, 2026-10-26 (evening).** A transaction at SNAPSHOT or SERIALIZABLE on PostgreSQL now
remembers each table it found to have no row in flight in its snapshot, and runs the vendor's own
statements on such a table: reads and locking reads as written, writes as written with the stamp,
a batch's entries as one such statement over a VALUES list (design 4.2 "A table found clear", 4.3
"The vendor's statement with the stamp"; todo 329, "the vendor's statements on a table the
transaction found clear"). Local laptop, round trip about 185 µs, 60 s, inline layout:

| | before | after |
|---|---|---|
| TPC-C (c)/(a) | 0.83 | **0.96** |
| TPC-C time added per transaction | +1.19 ms | **+0.26 ms** |
| TPC-C server CPU per commit, (a) → (c) | 1.26 → 2.19 ms | 1.31 → 1.53 ms |
| TPC-C p95 vs (a): Delivery, NewOrder, OrderStatus, Payment, StockLevel | 1.49, 1.18, 1.25, 1.19, 1.89 | 1.01, 1.04, 1.23, 1.08, 1.19 |
| YCSB (c)/(a), inline | 0.97 | **0.98** |
| YCSB p95 vs (a): Insert, Update, read-modify-write, Read, Scan | | 1.04, 1.01, 1.02, 1.05, 0.99 |
| TPC-C (c)/(a), decoupled | | 0.92, +0.53 ms a transaction |

**Update, 2026-10-26 (late): READ COMMITTED, the driver's default level.** Every run above was at
REPEATABLE READ. Measured at READ COMMITTED, the same build was at 0.43 of the vendor on TPC-C and
0.71 on YCSB: the locking read ran as four statements over several round trips, every read looked
its anchors up by two subqueries per row, and nothing of the clear-table path applies there, each
statement having its own snapshot. On PostgreSQL the driver now reads the anchors as columns of
each copy (an inline table's row, a decoupled table's metadata row by a left join), spells a `*`
out as the table's columns, and runs a locking read by key columns on an inline table as one
`FOR UPDATE` statement with the key, version and state as columns (design 4.2, 4.3; todo 329,
"READ COMMITTED, the default level"). Writes keep their forms there, which CAS on the version the
statement's snapshot read, as ScalarDB's own prepare does. Same laptop, both levels back to back in
one (busier) machine state, inline layout, 60 s:

| | REPEATABLE READ | READ COMMITTED before | READ COMMITTED after |
|---|---|---|---|
| TPC-C (c)/(a) | 0.91 | 0.43 | **0.86** |
| TPC-C time added per transaction | +0.64 ms | +7.7 ms | **+1.11 ms** |
| TPC-C server packets per commit, (a) → (c) | 19.4 → 19.5 | 19.5 → 44.3 | 19.4 → 19.8 |
| YCSB (c)/(a) | 0.98 | 0.71 | about 1.0 |
| TPC-C (c)/(a), decoupled | 0.92 | 0.44 | **0.82** (the lock in one round trip, same day) |

Left there, by size: Delivery's write beyond the key, the two-arm shape of a key read (one scan
with CASE per column would do), the slim write's keys CTE.

**Run (d), the layout alone.** The harness now has a fourth run, the vendor's driver on the tables
of (c), imported into ScalarDB and set up. It is a measurement, not a supported use: the vendor's
driver stamps no metadata and would read rows in flight, so it is sound only because no ScalarDB
transaction runs beside the benchmark. Locally at REPEATABLE READ, inline, TPC-C: (a) 1.00,
(d) **1.01**, (c) 0.92; YCSB (d) 0.98, (c) 0.98. ScalarDB's wider rows cost the vendor 20 to 37%
more server time per statement and nothing in throughput, their metadata columns staying NULL. The
driver's rows are wider still because it writes the stamp into each (WAL 15.8 KB a commit against
(d)'s 11.7 and (a)'s 10.1), which is the protocol's cost, not the layout's. The target stays (c)
against (a), the vendor on plain tables, which is what an application compares against; (d) only
attributes the gap, protocol against table shape, when deciding where to work, and is not part of
the standard matrix. Run with `-Dnativejdbc.benchbase.runs=a,c,d` (`BB_RUNS` on AWS) when that
question comes up.

The rest of this page is the state before these changes; its "Where the remaining cost goes" and
"Open ideas" are superseded by the todo's entry, which lists what is left: the first-touch reads'
rewritten shape, the INSERT's CTE and savepoint, the merged INSERT, the inline row's width, and
the decoupled layout's second row per write. Next: the same on AWS (`bench-env/run.sh`), both
layouts, with the time added per transaction as the number to compare.

A briefing for whoever looks for the next improvements (todo 329). The detail is in the files linked
below; this page says what is known, what was tried, and what is open.

## The goal

Through the driver, with every table on ScalarDB and no ScalarDB transaction running (BenchBase run
(c)), each major OLTP statement type costs at most 10% more than through the vendor's own URL (run
(a)): point and range reads, updates and deletes by key, inserts, locking reads, read-modify-write,
JDBC batches, and TPC-C's joins and aggregates. PostgreSQL first.

## Where it stands

AWS, PostgreSQL 16 on `m6id.2xlarge`, client `m6i.4xlarge`, 4 terminals, REPEATABLE READ (the
driver's SNAPSHOT), TPC-C scale 4, YCSB weights 50,5,15,10,0,20. Seventh run, `20261025-2251`, HEAD
`04213dc`, both of ScalarDB's layouts on the same machines:

| | decoupled | inline |
|---|---|---|
| TPC-C throughput vs vendor | 0.82 | 0.85 |
| TPC-C time added per transaction | +1.75 ms | +1.44 ms |
| TPC-C server statement time per commit (vendor 0.76 ms) | 1.73 ms | 1.51 ms |
| TPC-C WAL per commit (vendor 7.3-8.2 KB) | 18.6 KB | 16.2 KB |
| TPC-C p95 vs vendor: Delivery, NewOrder, OrderStatus, Payment, StockLevel | 1.46, 1.17, 1.21, 1.17, 1.94 | 1.41, 1.14, 1.16, 1.17, 2.03 |
| YCSB throughput vs vendor (eighth run, `20261026-0017`) | 0.94 | 0.96 |
| YCSB time added per operation (eighth run) | +69 µs | +39 µs |

- The seventh run's inline YCSB was void: its InsertRecord (`INSERT ... VALUES` with no column
  list) was refused on inline tables. Fixed in `4ff1c99`; the eighth run measured YCSB again, at
  the same round trip. Inline YCSB p50 added per operation: Insert +37 µs, Update +42,
  read-modify-write +70, Read +16, Scan +69.
- **Ratios depend on the round trip; do not read 0.82 as progress.** The seventh and eighth runs'
  round trip was about 360 µs, the sixth's 105 µs, on the same instance types in one subnet (no
  placement group, so EC2 may place the two anywhere in the zone). The decoupled code hardly changed
  between them, yet TPC-C went from 0.69 to 0.82 while the time it adds per transaction grew from
  1.23 to 1.75 ms. The vendor's rate falls as the round trip grows, so the
  ratio rises. Compare layouts or builds only within one run, and read "time added per transaction"
  (4 terminals: 4/tp(c) − 4/tp(a)) beside the ratio. At the sixth run's 105 µs, TPC-C was 0.69 and
  YCSB 0.86 (decoupled).
- Round trips are not the gap. Server packets per commit match the vendor's (about 20 for TPC-C,
  12 for YCSB). The gap is server time per statement, plus some client time.

## Where the remaining cost goes (TPC-C, seventh run, per repetition of 120 s)

Statement execution time from `pg_stat_statements` (`bench-env/analysis/pgss-sum.py` over each
run's `server.txt`):

| | vendor | decoupled | inline |
|---|---|---|---|
| writes | 14.4 s | 32.3 s | 25.1 s |
| reads | 9.3 s | 12.6 s | 14.6 s |
| planning | 0.5 s | 3.5 s | 3.2 s |

Largest single items, inline, per call against the vendor's:

- Delivery's `order_line` update by a prefix of the key (the fused, stamp-first form): 176 µs
  against 51 µs, about 20,000 calls a repetition. The largest single item.
- By-key updates (the slim form): district 48-49 µs against 14, customer 45-51 against 19-24,
  warehouse 39 against 16.
- A locking read (NewOrder's stock `FOR UPDATE`) runs a check of in-flight rows' before images
  beside it: 13 µs, about nine a transaction, on top of the read's 8 µs (vendor 10.5 µs alone).
- A plain read wrapped for in-flight rows (`item`): 12 µs against 6.
- StockLevel's count over `order_line` and `stock`: 1,351 µs against 880. Its coarse check and
  COUNT(DISTINCT) join are planned on every call.
- An INSERT with its metadata columns: `oorder` 60 µs against 35, `new_order` 42 against 24.

Local pgbench, one client, Unix socket, server CPU per statement (vendor / decoupled / inline):
keyed update 102 / 132 / 112 µs, insert 103 / 137 / 107, read 75 / 92 / 87. The decoupled layout's
floor is its second row per write, about 17 metadata rows per TPC-C transaction; the inline layout
has none.

## What has been done, and what each step gave

In order (todo 329's log has the measurements):

1. Reads without anchors at SNAPSHOT and SERIALIZABLE on PostgreSQL.
2. A JDBC batch's entries sent together under one savepoint, in one round trip.
3. Cheap wins: the batch's savepoint released by the next statement, autosave, the in-flight check
   prepared.
4. A batch of one prepared write merged into one statement (`VALUES` of entries, keys looked up
   per entry): TPC-C +14.4%, time added 2.06 → 1.52 ms.
5. The slim single-row write by the full key, tried first: TPC-C +5.1% (1.43 → 1.23 ms added),
   YCSB +2.4%.
6. Inline-layout one-statement forms (slim, merged, insert, fused beyond the key): local TPC-C 0.64
   → 0.86 (Delivery 4.46x → 1.43x); on AWS, against decoupled on the same machines, 0.82 → 0.85.
7. An inline INSERT without a column list (correctness; it unblocks YCSB on inline tables).

Weighed and set aside: **stamping at commit** instead of with each write. It would still write
every metadata row and save only what is left around it, which the merged and slim forms already
removed (todo 329, "stamping at commit, weighed and set aside").

Found not to be causes: plan caching (generic plans reused, StockLevel aside), and driver CPU on
YCSB (the client threads wait on the socket).

## Open ideas, roughly by size

1. **Inline fused write by a key prefix** (Delivery): 125 µs above the vendor's per call. The
   statement re-applies the predicate and folds the CAS into `tx_version` with `CASE`. A slim form
   for a prefix of the key, as for the full key, may close most of it.
2. **The read path.** The locking read's separate before-image check (about 0.12 ms a TPC-C
   transaction), and the plain read's wrapper (`CASE WHEN key IN (in-flight rows)`), about twice
   the vendor's for a point read. At the sixth run's round trip, YCSB's read was +11%, about 27 µs.
3. **StockLevel** (about 2x): its coarse check and join planned on every call.
4. **The slim write's per-call cost** over the vendor's (about +25-35 µs on TPC-C's hot rows):
   statement shape, the stamp's columns, wider rows (inline rows hold `before_*` columns too; WAL is
   about 2x the vendor's).
5. **The INSERT's savepoint at SNAPSHOT** (about 12 µs).
6. **pgjdbc's statement cache:** each merged form has a text per entry count, 2 to 64, twice. Fixed,
   padded sizes would bound it.
7. **Measurement:** a cluster placement group in `bench-env/terraform`, so the round trip stays the
   same from run to run.

## What must keep holding

The fast paths are only allowed where they give exactly what the step-by-step protocol gives
(design 4.4):

- A write stamps a fresh `tx_id` (ScalarDB's prepare and validation depend on it), version + 1,
  state 3.
- A write CASes on the anchor (`tx_id`, version) it read. `committedWhileItWaits` in
  `MoreWritesIntegrationTest` makes the anchor move while the write waits on the row's lock. It
  fails if either inline CAS is removed.
- A row in flight (a held ScalarDB prepare) is waited for or aborts. It is never written over.
- A batch's failure is undone to its start (savepoint), and the next statement still works.

## How to measure

- Locally: `BenchBaseBenchmark` (`driver/src/integrationTest/.../bench/`), runs (a), (c) and (d),
  `-Dnativejdbc.benchbase.layout=inline` for the inline layout,
  `-Dnativejdbc.benchbase.isolation=READ_COMMITTED` for the default level. The command is in
  [benchbase-postgresql-aws.md](benchbase-postgresql-aws.md) and `bench-env/matrix.sh`.
- AWS: `bench-env/run.sh plan` / `run` / `fetch` ([../plans/benchmark-environment.md](../plans/benchmark-environment.md)),
  with `BENCHBASE=1`, `BB_WORKLOADS`, `BB_RUNS`, `BB_LAYOUTS="decoupled inline"`, and `BASELINE=<commit>`
  to compare two builds on the same machines. A TPC-C plus YCSB run takes about 45 minutes. Each
  `terraform apply` needs the user's approval.
- Per statement: `python3 bench-env/analysis/pgss-sum.py <run>/server.txt` sums execution and
  planning time by kind. `bench-env/analysis/jfr-cpu.py` reads a client JFR recording
  (`BB_JVM_ARGS` with `-XX:StartFlightRecording`).
- Raw run output is under `bench-env/results/<run>/` (git-ignored, on the machine that ran it).

## Files

- [benchbase-postgresql-aws.md](benchbase-postgresql-aws.md): every cloud run's tables.
- `todos/329-pending-p1-oltp-overhead-within-10-percent.md`: the work log, step by step, with local
  measurements and reviews.
- [../design.md](../design.md) section 4.4: the merged, slim and fused write forms; 4.2 for reads.
- `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Writes.java` (write plans and their SQL),
  `Proxies.java` (batches), `Router.java` (routing, the in-flight check).
- `driver/src/integrationTest/.../jdbc/MoreWritesIntegrationTest.java`, `InlineIntegrationTest.java`:
  the behaviour the fast paths must keep.
