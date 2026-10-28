# BenchBase on AWS, PostgreSQL 16, tier A (2026-10-25)

Run `20261025-1006` of `bench-env/run.sh` (`BENCHBASE=1 READS=0 REPS=3 BASELINE=ef77549`), the
first BenchBase run off the laptop (todo 329, milestone T6 of `testing.md`).

- **Database:** `m6id.2xlarge` (8 vCPU, 32 GB), PostgreSQL 16.15 in the official image with host
  networking, data on the local NVMe, `shared_buffers` 8 GB, pg_stat_statements loaded with
  planning tracked (both runs alike).
- **Client:** `m6i.4xlarge`, same subnet and availability zone; JDK 25, BenchBase `33c00473`.
- **Workloads:** TPC-C scale 4 and YCSB (weights 50,5,15,10,0,20), 4 terminals, 120 s a run,
  3 repetitions (medians), REPEATABLE READ (the driver's SNAPSHOT), assertions off.
- **Trees:** HEAD `93f5217` (todo 329's steps 1 and 2 and the batch's release) and the baseline
  `ef77549`, the BenchBase harness before todo 329.
- About 1 h 5 min of instances, about $1.35.

## Summary

| | baseline (c)/(a) | HEAD (c)/(a) | laptop HEAD, same day |
|---|---|---|---|
| TPC-C throughput | 0.59 | **0.76** | 0.77-0.78 |
| YCSB throughput | 0.70 | **0.92** | 0.90 |

p50 against (a), HEAD:

| TPC-C | NewOrder | Payment | OrderStatus | Delivery | StockLevel |
|---|---|---|---|---|---|
| (a) µs | 11,137 | 3,077 | 1,527 | 26,866 | 2,114 |
| (c) µs | 14,096 (+27%) | 3,707 (+20%) | 1,690 (+11%) | 39,367 (+47%) | 3,963 (+87%) |

| YCSB | Read | Scan | Update | Insert | read-modify-write |
|---|---|---|---|---|---|
| (a) µs | 724 | 1,700 | 797 | 793 | 1,173 |
| (c) µs | 747 (+3%) | 1,948 (+15%) | 914 (+15%) | 855 (+8%) | 1,320 (+13%) |

What the server counted per commit, HEAD (pg_stat_database, the container's cgroup, the host's
packets):

| | active ms | idle in transaction ms | WAL KB | server CPU ms | packets sent |
|---|---|---|---|---|---|
| TPC-C (a) | 0.369 | 6.577 | 8.3 | 0.899 | 19.7 |
| TPC-C (c) | 0.902 | 6.951 | 19.1 | 2.821 | 20.2 |
| YCSB (a) | 0.159 | 0.458 | 0.4 | 0.186 | 11.8 |
| YCSB (c) | 0.205 | 0.480 | 0.5 | 0.256 | 11.8 |

## Findings

- **The laptop's ratios held.** TPC-C 0.76 against 0.77-0.78, YCSB 0.92 against 0.90: the network
  between client and server did not change the picture. Steps 1 and 2 of todo 329 and the batch's
  release are worth +17 points on TPC-C and +22 on YCSB here too.
- **Round trips are equal** (20.2 against 19.7 packets a TPC-C commit), and a round trip is about
  0.3 ms here: what the batches saved shows in the baseline's ratio, and is gone.
- **The server's CPU per TPC-C commit triples** (0.90 to 2.82 ms), its active time +0.53 ms, its WAL
  2.3x: the second row per write. Per NewOrder item, the fused stock update takes 54 µs and 17 µs
  of planning on average against 7.5 µs, the fused order_line insert 40 µs against 21 µs. The
  planning is the batch texts' warm-up, still 3.6 s of the stock update's in a 120 s run.
- **Unexplained, about 1.4 ms a TPC-C transaction.** A transaction takes 2.3 ms longer at the
  client (4 terminals: 7.6 against 9.9 ms), but the server's active and idle-in-transaction time
  account for 0.9 ms of it. The rest is either the driver's own work on the client (not measured
  here) or time outside the server's transactions. Next: run (b), the driver with every table plain
  (routing only), and a profile of the client.
- YCSB's per-operation overheads are close: reads +3%, inserts +8%, writes and scans +13-15%.

## Second run: routing alone, and the client's CPU (2026-10-25)

Run `20261025-1135` (`BENCHBASE=1 READS=0 REPS=2 BB_RUNS=a,b,c`, a JFR recording of the BenchBase
JVM per run with CPU-time sampling), HEAD `c7f0e5b`, the same machine types. The vendor's TPC-C ran
at 711/s against 528/s in the first run (another placement, a shorter round trip), so the two runs'
ratios are not comparable with each other; (a), (b) and (c) within one run are.

| | (a) vendor | (b) driver, every table plain | (c) driver, ScalarDB tables |
|---|---|---|---|
| TPC-C throughput | 711.5 | 704.6 (0.99) | 506.5 (0.71) |
| YCSB throughput | 5,026 | 4,954 (0.99) | 4,475 (0.89) |

**Routing costs about 1%**: (b) is at 0.99 on both workloads and every transaction type within
4%. What remains is the rewrite. Per TPC-C commit:

| | (a) | (b) | (c) |
|---|---|---|---|
| client CPU (BenchBase's JVM, JFR) | 0.26 ms | 0.35 ms | 0.66 ms |
| server statements, execution and planning (pg_stat_statements) | 0.41 ms | 0.39 ms | 1.13 ms |
| server active (pg_stat_database) | 0.40 ms | 0.38 ms | 0.91 ms |
| server CPU (the container's cgroup) | 0.96 ms | 0.94 ms | 2.84 ms |
| transaction at the client (4 terminals / throughput) | 5.62 ms | 5.68 ms | 7.90 ms |

A (c) transaction takes 2.3 ms longer: its statements +0.72 ms on the server (the second row per
write; planning 6.1 s a run, mostly the batch texts' warm-up and StockLevel's join), the driver's
CPU +0.40 ms on the client, and about 1.2 ms of server CPU outside the statements' execution: the
protocol of the batch texts, the commit, and background work (the WAL writer, autovacuum of the
extra rows), which these counters do not separate. The client's (c) samples in the driver:
`LockPlan.applyFused` 12%, `applyTogether` 10%, `KeyedPlan.applyFused` 8%, `Run.prepare` 5%,
`Run.<init>` 3% (`UUID.randomUUID`), `readKey` 3%; the rest is pgjdbc's encoding and decoding.

YCSB (c), p50 against (a): Read +4%, Insert +11%, Scan +15%, Update +17%, read-modify-write +18%.

## Third run: the server's CPU per process (2026-10-25)

Run `20261025-1253` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=tpcc BB_RUNS=a,c`), HEAD `f163cb8`,
with the harness sampling `pg_stat_activity` and `/proc/<pid>/stat` every 200 ms. This placement's
round trip was shorter again: (a) 1,151/s, (c) 724/s, **0.63**. Over the three runs the vendor ran
at 528, 711 and 1,151/s and (c) at 0.76, 0.71 and 0.63 of it: the driver adds about 2 ms a TPC-C
transaction whatever the network, so the ratio falls as the round trip shortens.

| per TPC-C commit | (a) | (c) |
|---|---|---|
| server CPU, all (the container's cgroup) | 0.89 ms | 2.78 ms |
| the client backends | 0.86 ms | 2.70 ms |
| every other process (autovacuum, WAL writer, checkpointer) | 0.02 ms | 0.07 ms |
| the busy backends' samples `active` | 20% | 43% |

**The extra server CPU is the backends' own**, on the transaction's path, not background work. It
is more than pg_stat_statements' execution and planning (+0.72 ms in the second run): what each
statement costs around its execution, as binding its parameters, locking its relations and setting
up the executor for a plan of several data-modifying CTEs.

**A batch's writes as one statement** (pgbench on the laptop, `-M prepared`, 4 clients, a
transaction updating 15 random `stock` rows by key, its statements pipelined in one round trip):

| | latency |
|---|---|
| 15 plain UPDATEs, the vendor's | 1.59 ms |
| 15 fused UPDATEs, the driver's today | 2.44 ms (+53%) |
| one fused UPDATE of the 15 keys | **1.51 ms** (-5%) |

The merged statement reads its keys with `= ANY(ARRAY[...])` in the predicate on the committed view;
the keys as a joined `VALUES` list scanned the whole view instead (400 ms).

## Fourth run: a batch's entries as one statement (2026-10-25)

Run `20261025-1419` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=tpcc BB_RUNS=a,c
BASELINE=aca4d97`): HEAD `8599a2c`, which merges a batch's entries of one prepared write, against
`aca4d97` just before it, on the same machines. The shortest round trip of the four runs: (a)
1,786/s.

| TPC-C | (a) | (c) before, `aca4d97` | (c) merged, `8599a2c` |
|---|---|---|---|
| throughput | 1,786 | 930 (0.52) | **1,065 (0.60)**, +14.4% |
| NewOrder p50 / p95 µs | 3,253 / 4,602 | 6,130 / 9,189 | **4,835 / 6,882** |
| server CPU per commit | 0.96 ms | 2.81 ms | **2.27 ms** |

NewOrder's two batches, its stock updates and its order_line inserts, are one statement each now.
What remains is the single-row writes, a fused statement each: Payment +76% at p50 (885 against
1,554 µs: its warehouse, district and customer updates), Delivery +126% (its ten districts'
writes one at a time), StockLevel +89% (its join's planning and arms).

## Fifth run: tier A and RDS side by side, round trips, a profile (2026-10-25)

Run `20261025-1558` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=tpcc BB_RUNS=a,c`, a JFR recording
with CPU-time sampling per run), HEAD `a119c5d`, both tiers at once from one client: tier A as
before; tier B RDS for PostgreSQL 16.13, `db.m5.2xlarge`, 100 GB gp3, single-AZ in the client's
zone, default parameters. `perf-tier-a.sh` profiled tier A's host during the first repetition of
(a) and (c), which slowed it by up to 10% (vendor 811 against 896/s in the second).

| TPC-C | round trip µs, median / p99 | (a) | (c) | (c)/(a) |
|---|---|---|---|---|
| tier A | 222 / 255 | 853.6 | 549.8 | 0.64 (0.62 in the second repetition) |
| RDS | 114 / 155 | 905.4 | 627.0 | **0.69** |

RDS's round trip was the shorter one here. Per commit:

| | tier A (a) | tier A (c) | RDS (a) | RDS (c) |
|---|---|---|---|---|
| active ms | 0.365 | 1.236 | 1.527 | 2.667 |
| idle in transaction ms | 3.807 | 4.967 | 2.285 | 2.587 |
| server CPU ms | 0.878 | 2.207 | n/a | n/a |
| client CPU ms (JFR) | 0.275 | 0.608 | 0.356 | 0.650 |

- On RDS the commit's WAL flush to EBS is in every transaction: the busy backends wait on
  `IO:WALSync` in 11-12% of the samples (under 1% on tier A), which both runs pay, and the ratio is
  higher. RDS p50 against (a): NewOrder +33%, Payment +32%, OrderStatus +32%, Delivery +111%,
  StockLevel +128%.
- Tier A's (c) transaction took 2.6 ms longer, against 1.5 ms in the fourth run, with the server's
  extra CPU the same (+1.33 ms against +1.31): the extra is idle in transaction (+1.16 against +0.22
  ms), the client's side. The client's CPU was +0.33 ms; the rest is not explained by this run,
  which also ran two BenchBase JVMs side by side and sampled their CPU every millisecond.
- The profile (perf at 49 Hz, the backends' own time; call stacks did not unwind) is flat. Grouped,
  the executor's setup per execution (expressions, tuple descriptors, slots) grows from 4.2% of the
  backends' samples in (a) to 7.2% in (c), of 2.4 times the CPU; memory contexts (7%) and hash,
  catalog and lock lookups (6%) keep their share of it. The extra server CPU is what each statement
  costs around its rows, which grows with the size of its plan.

## Sixth run: the slim single-row write (2026-10-25)

Run `20261025-1806` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=tpcc,ycsb BB_RUNS=a,c
BASELINE=f8ce61e`): HEAD `7d3abbf`, whose single write by the key alone runs the slim statement
first, against `f8ce61e`, merged batches without it, on the same machines; round trip about 100 µs,
no profiling, no JFR.

| | baseline `f8ce61e` | slim `7d3abbf` |
|---|---|---|
| TPC-C (c), (a) 1,432-1,442/s | 951.7 (0.66) | **999.9 (0.69)**, +5.1% |
| TPC-C added per transaction | +1.43 ms | +1.23 ms |
| TPC-C server CPU per commit, (a) 0.85 ms | 2.08 ms | 1.86 ms |
| YCSB (c), (a) 8,521-8,547/s | 7,143 (0.84) | **7,317 (0.86)**, +2.4% |
| YCSB added per operation | +92 µs | +79 µs |

p50 against (a), baseline then slim: Payment +52% then +37%, Delivery +97% then +81%, NewOrder +36%
both (its writes merged already), StockLevel +106% then +110%; YCSB Update +39% then +23%,
read-modify-write +34% then +24%, Read +11% both, Scan +13% then +16%, Insert +18% then +19%.
At this round trip a YCSB operation takes the vendor about 0.47 ms, so its reads' +11% is about 27
µs: the in-flight check of the rewritten read.

## Seventh run: the decoupled and inline layouts side by side (2026-10-25)

Run `20261025-2251` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=tpcc,ycsb BB_RUNS=a,c
BB_LAYOUTS="decoupled inline"`): HEAD `04213dc`, run (c)'s tables in ScalarDB's decoupled layout
and then in its inline one, on the same machines. The round trip was 364 µs, against the sixth
run's 105 µs (the same subnet, no placement group), so the vendor's rates are about a third of the
sixth run's and the ratios do not compare with its; the two layouts compare with each other.

| | decoupled | inline |
|---|---|---|
| TPC-C (a) | 518.5/s | 508.7/s |
| TPC-C (c) | 422.7 (0.82) | **429.9 (0.85)** |
| TPC-C added per transaction | +1.75 ms | +1.44 ms |
| TPC-C server CPU per commit, (a) then (c) | 0.96, 2.10 ms | 0.99, 1.99 ms |
| TPC-C statement time per commit, (a) then (c) | 0.76, 1.73 ms | 0.76, 1.51 ms |
| TPC-C WAL per commit, (a) then (c) | 8.2, 18.6 KB | 7.3, 16.2 KB |
| YCSB (c), (a) 3,842-3,878/s | 3,592.5 (0.93) | not valid: every InsertRecord refused |

p95 against (a), decoupled then inline: Delivery 1.46 then 1.41, NewOrder 1.17 then 1.14,
OrderStatus 1.21 then 1.16, Payment 1.17 both, StockLevel 1.94 then 2.03.

The inline layout writes in less statement time (TPC-C's writes 32.3 s a repetition decoupled,
25.1 s inline, 14.4 s the vendor's) but reads in more (12.6 s decoupled, 14.6 s inline, 9.3 s the
vendor's): its locking read's check of in-flight rows' before images runs beside it (13 µs, about
nine a transaction), and a plain read of `item` takes 12 µs to the vendor's 6. The largest single
statement is Delivery's `order_line` update by a prefix of the key, the inline fused form, 176 µs a
call to the vendor's 51. Its slim updates take 39-51 µs to the vendor's 14-24.

YCSB's InsertRecord is `INSERT INTO usertable VALUES (...)` without a column list, which the driver
refuses on an inline table (0AN01, its metadata columns beside the user's); the run counts the
refusals as unexpected errors, so its (c) throughput is not the workload's. Its other operations'
p95 against (a), decoupled then inline: Read 1.06 then 1.46 (p50 776 then 761 µs), Update 1.10
then 1.09, read-modify-write 1.11 then 1.10, Scan 1.10 then 0.97.

## Eighth run: YCSB on both layouts, an inline INSERT without a column list (2026-10-26)

Run `20261026-0017` (`BENCHBASE=1 READS=0 REPS=2 BB_WORKLOADS=ycsb BB_RUNS=a,c
BB_LAYOUTS="decoupled inline"`): HEAD `4ff1c99`, whose inline tables take an INSERT without a column
list, the seventh run's void YCSB again. Round trip 354-365 µs, as the seventh run's.

| | decoupled | inline |
|---|---|---|
| YCSB (a) | 3,974.8/s | 3,904.5/s |
| YCSB (c) | 3,720.8 (0.94) | **3,763.1 (0.96)** |
| YCSB added per operation | +69 µs | +39 µs |
| server CPU per commit, (a) then (c) | 0.187, 0.235 ms | 0.194, 0.211 ms |

p50 added against (a), decoupled then inline: Insert +53 then +37 µs, Update +61 then +42,
read-modify-write +90 then +70, Read +18 then +16, Scan +202 then +69. p95 against (a), inline:
Insert 1.05, Update 1.05, read-modify-write 1.06, Read 1.05, Scan 1.03; decoupled 1.08, 1.10, 1.10,
1.03, 1.12. Every inline InsertRecord completed (46,037, none unexpected).

At the sixth run's 105 µs round trip the same code gave TPC-C 0.69 and YCSB 0.86 decoupled; the
seventh and eighth runs' 0.82 and 0.94 come from the longer round trip, which the vendor pays as
well (time added per TPC-C transaction 1.23 ms then 1.75 ms). Both runs had both instances in one
subnet (us-east-1a) and no placement group.

## Ninth run: three levels, both layouts, two round trips (2026-10-26)

Run `20261026-1433` (`BENCHBASE=1 READS=0 BB_LAYOUTS="decoupled inline" BB_ISOLATIONS="READ_COMMITTED
REPEATABLE_READ SERIALIZABLE" NET_DELAYS="0 250"`): `6c19378` (the driver as of `86e16ee`), runs (a)
and (c), TPC-C scale 4 and YCSB, 4 terminals, 3 repetitions of 120 s, the median shown. For the first
time with `placement_strategy = "cluster"`: both instances in one placement group, a `SELECT 1`
round trip of 68-84 µs. The second half ran on the same instances with `net-delay.sh` adding 250 µs
to every packet the client sent to the database (TCP connect 119 → 382 µs), a 327-332 µs round
trip, the seventh and eighth runs' (354-365 µs). Each level's two layouts ran back to back. "Added"
is 4/(c) − 4/(a): per transaction for TPC-C, per operation for YCSB. SNAPSHOT is the driver's
REPEATABLE_READ, PostgreSQL's REPEATABLE READ in run (a); SERIALIZABLE is SERIALIZABLE on both sides.

TPC-C, fast (about 70 µs):

| level | layout | (a)/s | (c)/s | (c)/(a) | goodput (c)/(a) | added | p95 (a) / (c) ms | packets/commit (a) / (c) | server CPU ms/commit (a) / (c) | WAL KB/commit (a) / (c) |
|---|---|---|---|---|---|---|---|---|---|---|
| READ COMMITTED | decoupled | 1,769 | 676 | 0.38 | 0.38 | +3.66 ms | 4.8 / 14.4 | 19.7 / 44.5 | 0.877 / 2.608 | 8.0 / 18.3 |
| READ COMMITTED | inline | 1,797 | 1,393 | 0.78 | 0.77 | +0.65 ms | 4.8 / 5.8 | 19.7 / 20.1 | 0.883 / 1.408 | 7.7 / 15.0 |
| SNAPSHOT | decoupled | 1,808 | 1,414 | 0.78 | 0.78 | +0.61 ms | 4.8 / 5.9 | 19.6 / 19.6 | 0.877 / 1.315 | 7.6 / 20.3 |
| SNAPSHOT | inline | 1,763 | 1,531 | 0.87 | 0.86 | +0.34 ms | 4.8 / 5.5 | 19.7 / 19.7 | 0.886 / 1.100 | 7.6 / 15.3 |
| SERIALIZABLE | decoupled | 1,597 | 1,261 | 0.79 | 0.86 | +0.67 ms | 6.9 / 9.6 | 25.8 / 23.7 | 1.137 / 1.629 | 7.5 / 21.2 |
| SERIALIZABLE | inline | 1,635 | 1,520 | 0.93 | 1.05 | +0.19 ms | 6.7 / 5.6 | 25.0 / 19.8 | 1.130 / 1.171 | 7.9 / 14.7 |

TPC-C, slow (about 330 µs):

| level | layout | (a)/s | (c)/s | (c)/(a) | goodput (c)/(a) | added | p95 (a) / (c) ms | packets/commit (a) / (c) | server CPU ms/commit (a) / (c) | WAL KB/commit (a) / (c) |
|---|---|---|---|---|---|---|---|---|---|---|
| READ COMMITTED | decoupled | 546 | 229 | 0.42 | 0.42 | +10.12 ms | 14.7 / 43.1 | 19.5 / 43.6 | 0.925 / 2.654 | 7.6 / 23.3 |
| READ COMMITTED | inline | 551 | 492 | 0.89 | 0.89 | +0.87 ms | 14.6 / 15.8 | 19.5 / 20.0 | 0.934 / 1.489 | 8.7 / 15.2 |
| SNAPSHOT | decoupled | 542 | 502 | 0.93 | 0.92 | +0.60 ms | 14.8 / 15.7 | 19.6 / 19.5 | 0.938 / 1.394 | 8.2 / 19.5 |
| SNAPSHOT | inline | 553 | 523 | 0.95 | 0.94 | +0.41 ms | 14.5 / 15.2 | 19.5 / 19.6 | 0.930 / 1.173 | 8.2 / 16.3 |
| SERIALIZABLE | decoupled | 495 | 423 | 0.85 | 0.75 | +1.39 ms | 23.2 / 27.8 | 24.3 / 30.0 | 1.240 / 2.191 | 9.0 / 24.7 |
| SERIALIZABLE | inline | 483 | 516 | 1.07 | 1.15 | -0.52 ms | 24.9 / 15.5 | 24.4 / 19.6 | 1.157 / 1.302 | 9.3 / 16.5 |

YCSB, fast:

| level | layout | (a)/s | (c)/s | (c)/(a) | goodput (c)/(a) | added | p95 (a) / (c) ms | packets/commit (a) / (c) | server CPU ms/commit (a) / (c) | WAL KB/commit (a) / (c) |
|---|---|---|---|---|---|---|---|---|---|---|
| READ COMMITTED | decoupled | 10,000 | 3,266 | 0.33 | 0.33 | +824.87 µs | 1.6 / 5.9 | 11.8 / 13.3 | 0.191 / 0.839 | 0.4 / 0.5 |
| READ COMMITTED | inline | 9,780 | 8,438 | 0.86 | 0.86 | +65.05 µs | 1.6 / 2.0 | 11.7 / 12.3 | 0.191 / 0.227 | 0.4 / 0.4 |
| SNAPSHOT | decoupled | 9,750 | 8,273 | 0.85 | 0.85 | +73.25 µs | 1.6 / 1.9 | 11.8 / 11.8 | 0.196 / 0.248 | 0.4 / 0.5 |
| SNAPSHOT | inline | 9,738 | 8,612 | 0.88 | 0.88 | +53.74 µs | 1.6 / 1.9 | 11.8 / 11.9 | 0.197 / 0.228 | 0.4 / 0.4 |
| SERIALIZABLE | decoupled | 9,213 | 7,902 | 0.86 | 0.86 | +72.02 µs | 1.7 / 2.0 | 11.8 / 11.9 | 0.212 / 0.267 | 0.4 / 0.5 |
| SERIALIZABLE | inline | 9,300 | 8,283 | 0.89 | 0.89 | +52.78 µs | 1.7 / 1.9 | 11.8 / 11.8 | 0.218 / 0.246 | 0.4 / 0.4 |

YCSB, slow:

| level | layout | (a)/s | (c)/s | (c)/(a) | goodput (c)/(a) | added | p95 (a) / (c) ms | packets/commit (a) / (c) | server CPU ms/commit (a) / (c) | WAL KB/commit (a) / (c) |
|---|---|---|---|---|---|---|---|---|---|---|
| READ COMMITTED | decoupled | 4,169 | 2,333 | 0.56 | 0.56 | +755.19 µs | 2.1 / 4.7 | 11.7 / 13.3 | 0.193 / 0.571 | 0.4 / 0.5 |
| READ COMMITTED | inline | 4,102 | 3,873 | 0.94 | 0.94 | +57.59 µs | 2.1 / 2.5 | 11.7 / 12.3 | 0.190 / 0.218 | 0.4 / 0.4 |
| SNAPSHOT | decoupled | 4,216 | 3,889 | 0.92 | 0.92 | +79.73 µs | 2.1 / 2.3 | 11.8 / 11.9 | 0.184 / 0.227 | 0.4 / 0.5 |
| SNAPSHOT | inline | 4,122 | 3,915 | 0.95 | 0.95 | +51.24 µs | 2.2 / 2.3 | 11.8 / 11.9 | 0.203 / 0.218 | 0.4 / 0.4 |
| SERIALIZABLE | decoupled | 4,017 | 3,711 | 0.92 | 0.92 | +82.30 µs | 2.3 / 2.6 | 11.8 / 11.9 | 0.216 / 0.268 | 0.4 / 0.5 |
| SERIALIZABLE | inline | 4,005 | 3,897 | 0.97 | 0.97 | +27.55 µs | 2.3 / 2.3 | 11.8 / 11.8 | 0.217 / 0.225 | 0.4 / 0.4 |

- **The driver adds a time per transaction that hardly depends on the round trip**, wherever it
  sends as many packets as the vendor: SNAPSHOT decoupled +0.61 then +0.60 ms, SNAPSHOT inline +0.34
  then +0.41 ms, READ COMMITTED inline +0.65 then +0.87 ms. The ratio moves with the vendor's own
  rate: SNAPSHOT inline is 0.87 fast and 0.95 slow. At about 330 µs SNAPSHOT on both layouts,
  SERIALIZABLE inline and READ COMMITTED inline's YCSB are within 10%; READ COMMITTED inline's TPC-C
  is 0.89.
- **READ COMMITTED on decoupled tables** sent 44.5 packets a TPC-C commit against 19.7, so its added
  time grew with the round trip: +3.66 ms fast, +10.1 ms slow. Its locking read was still the
  step-by-step one (the one-statement lock at that level was inline-only); in `pg_stat_statements`
  the before-image check ran 1.63 million times in a repetition against none on inline. `816f506`
  gives decoupled tables a one-round-trip locking read at that level; its own run follows.
- **SERIALIZABLE on decoupled tables at the long round trip** lost more transactions to
  serialization failures than the vendor: NewOrder retried 90,394 times against 45,735 (completed
  41,313 against 66,807), goodput 0.75, 30.0 packets a commit against 24.3. Fast, the same cell's
  goodput was 0.86. The driver's inline tables at SERIALIZABLE had far fewer retries than the vendor
  (NewOrder 2,406 against 59,130 slow; Payment 12,312 against 30,149), hence goodput above 1.
- **A `SHOW max_parallel_workers_per_gather` per transaction at SERIALIZABLE on decoupled tables**:
  111,003 calls in one fast TPC-C repetition (about 0.76 a commit), 37,500 slow, 15,520 in YCSB,
  against about 1,200 on inline and a few hundred at the other levels. `Router.switchWorkers` reads
  the setting again whenever it no longer knows it, as after a rollback; each read is a round trip.
- Statement time, fast TPC-C, one repetition, (a) then (c) (`pgss-sum.py`): SNAPSHOT decoupled 81.5
  then 102.8 s with 1.1 then 2.5 s planning; READ COMMITTED inline 82.8 then 98.2 s, of which the inline write beyond the key (Delivery) 28.2 s over 140,831
  calls, 200 µs a call.

The raw output is in `bench-env/results/20261026-1433/` (git-ignored).

## Tenth run: decoupled READ COMMITTED with its one-round-trip locking read (2026-10-26)

Run `20261026-2036` (`SOURCE=816f506 BENCHBASE=1 READS=0 BB_LAYOUTS=decoupled
BB_ISOLATIONS=READ_COMMITTED NET_DELAYS="0 250"`): `816f506` alone, which differs from the ninth
run's code only in the decoupled locking read at READ COMMITTED (`Router.lockingRead`,
`Writes.LockPlan`), sent as that commit rather than the shared working tree. A new cluster placement
group, round trips 70 and 331-333 µs as the ninth run's. Before is the ninth run's cell, after this
run's; SNAPSHOT decoupled is the ninth run's, for scale.

| | before | after | SNAPSHOT decoupled |
|---|---|---|---|
| TPC-C fast (c)/(a), added per transaction | 0.38, +3.66 ms | **0.66, +1.19 ms** | 0.78, +0.61 ms |
| TPC-C slow | 0.42, +10.1 ms | **0.83, +1.48 ms** | 0.93, +0.60 ms |
| YCSB fast, added per operation | 0.33, +825 µs | **0.66, +214 µs** | 0.85, +73 µs |
| YCSB slow | 0.56, +755 µs | **0.81, +223 µs** | 0.92, +80 µs |
| TPC-C packets per commit, (a) then (c) | 19.7, 44.5 | 19.7, **20.0** | 19.6, 19.6 |
| TPC-C active ms per commit, fast, (a) then (c) | 0.383, 1.394 | 0.386, 1.069 | 0.377, 0.794 |
| TPC-C server CPU ms per commit, fast, (a) then (c) | 0.877, 2.608 | 0.913, 1.985 | 0.877, 1.315 |
| TPC-C p95 ms, fast, (a) then (c) | 4.8, 14.4 | 4.9, 7.4 | 4.8, 5.9 |
| YCSB p95 ms, fast, (a) then (c) | 1.6, 5.9 | 1.7, 3.2 | 1.6, 1.9 |

The packets per commit are the vendor's again: the step-by-step lock is gone. What is left at READ
COMMITTED is server time per statement. Fast TPC-C, statement time per commit (`pg_stat_statements`,
one repetition): 970 µs against SNAPSHOT decoupled's 613. The largest differences, READ COMMITTED
then SNAPSHOT per commit:

- The stock lock: 24.7 µs a call, 4.51 a commit, plus the metadata read beside it, 6.1 µs a call;
  at SNAPSHOT the lock is the vendor's statement, 10 µs a call. The lock's text (cut at 400
  characters in `server.txt`) carries correlated subqueries into `stock_tx_metadata` for the anchor
  columns, which the second statement reads again.
- Delivery's `order_line` update beyond the key, the keys-CTE form: 244 µs a call, against the
  SNAPSHOT form's 143 µs.
- Keyed writes in the keys-CTE form (district, customer, warehouse): about 125 µs a commit, where
  SNAPSHOT writes on tables it found clear with the vendor's statements.
- The item read with its version columns, 53 against 21 µs a commit; the merged batch, 149
  against 93; StockLevel, 77 against 40.

pgss-sum.py counts the lock's second statement as "read: before images of in-flight rows", since
it names `before_tx_id`; it is the one-round-trip lock's version read (6.1 µs), not the step-by-step
check. SAVEPOINT ran 632 times in a repetition, ROLLBACK 513, SHOW 640.

## Eleventh run: decoupled at three levels from `faae2ed` (2026-10-27)

Run `20261026-2334` (`SOURCE=faae2ed BENCHBASE=1 READS=0 BB_WORKLOADS=tpcc BB_LAYOUTS=decoupled
BB_ISOLATIONS="READ_COMMITTED REPEATABLE_READ SERIALIZABLE" NET_DELAYS="0 250"`), TPC-C only, 3
repetitions. `faae2ed` has todo 336's decoupled READ COMMITTED forms (the lock keys-only over the
rows as they are; the keyed write and the write beyond the key as the clear-table statement with
the CAS in its stamp) and `85f4adc` (a rollback keeps the workers setting: no SHOW per retried
transaction), besides the Oracle session's work since `816f506`. A new cluster placement group.
The last column is the earlier cell on other instances: READ COMMITTED against the tenth run,
SNAPSHOT and SERIALIZABLE against the ninth.

| level | (a)/s | (c)/s | (c)/(a) | goodput (c)/(a) | added | packets/commit (a) / (c) | active ms/commit (a) / (c) | server CPU ms/commit (a) / (c) | p95 ms (a) / (c) | earlier: (c)/(a), goodput, added | round trip µs |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **fast** | | | | | | | | | | | |
| READ COMMITTED | 1,853 | 1,315 | **0.71** | 0.71 | +0.88 ms | 19.7 / 19.7 | 0.362 / 0.950 | 0.867 / 1.707 | 4.7 / 6.4 | 0.66, 0.66, +1.19 ms (tenth) | 68 |
| SNAPSHOT | 1,882 | 1,480 | **0.79** | 0.78 | +0.58 ms | 19.7 / 19.7 | 0.381 / 0.799 | 0.900 / 1.352 | 4.6 / 5.6 | 0.78, 0.78, +0.61 ms (ninth) | 68 |
| SERIALIZABLE | 1,597 | 1,276 | **0.80** | 0.78 | +0.63 ms | 25.6 / 25.9 | 0.579 / 1.061 | 1.205 / 1.832 | 6.9 / 9.2 | 0.79, 0.86, +0.67 ms (ninth) | 65 |
| **slow** | | | | | | | | | | | |
| READ COMMITTED | 548 | 480 | **0.88** | 0.87 | +1.04 ms | 19.5 / 19.5 | 0.424 / 0.968 | 0.991 / 1.797 | 14.6 / 16.5 | 0.83, 0.83, +1.48 ms (tenth) | 331 |
| SNAPSHOT | 551 | 502 | **0.91** | 0.91 | +0.71 ms | 19.5 / 19.5 | 0.385 / 0.839 | 0.944 / 1.480 | 14.6 / 15.7 | 0.93, 0.92, +0.60 ms (ninth) | 329 |
| SERIALIZABLE | 467 | 429 | **0.92** | 0.89 | +0.74 ms | 26.2 / 26.4 | 0.582 / 1.070 | 1.293 / 2.088 | 25.3 / 27.3 | 0.85, 0.75, +1.39 ms (ninth) | 332 |

- **READ COMMITTED, decoupled:** +1.19 → +0.88 ms a transaction fast, +1.48 → +1.04 ms slow;
  statement time 970 → 790 µs a commit (fast, one repetition, execution and planning). Per call
  against the tenth run: the lock pair 30.8 → 22.4 µs a stock row (the lock 24.6 → 10.0; its
  versions read 6.2 → 12.4, the same text, now the metadata row's first touch, which the lock's
  subqueries paid before), Delivery's `order_line` write 246 → 168 µs (the
  `scalardb_native_before` form), the merged stock batch 331 → 218 µs, the new-order read 63 → 28
  µs. Against SNAPSHOT in this run, per commit: the keyed writes (district, customer, warehouse)
  88.0 against 81.9 µs; StockLevel 45 µs executing and 32 planning against 38 and 5 (planned 5,530
  times in 6,593 calls, 776 µs a plan against 117); planning in all 7.13 s a repetition against
  2.80 (vendor 1.21), StockLevel 5.11 s of it. What is left against SNAPSHOT (+0.58 ms) is about
  0.3 ms a transaction; todo 337 (`cb16d83`) takes part of the lock pair and of StockLevel.
- **SNAPSHOT, decoupled:** as in the ninth run, within the two runs' spread: the changes since
  `6c19378`, the Oracle work among them, cost PostgreSQL nothing visible.
- **SERIALIZABLE, decoupled:** no `SHOW max_parallel_workers_per_gather` in any repetition (ninth
  run: 111,003 in one fast repetition). At the long round trip the cell recovered: 0.85 → 0.92,
  goodput 0.75 → 0.89, +1.39 → +0.74 ms; NewOrder's retries 90,394 → 69,467 against the vendor's
  52,375 (ninth 45,735), completed 41,313 → 49,469 against 61,319. Fast, goodput fell from 0.86 to
  0.78 while the throughput ratio stayed (0.79 → 0.80): the vendor's own retries moved as much
  (NewOrder 142,320 → 174,272), so read the fast goodput as the spread of SSI aborts between runs.
  Packets per commit equal the vendor's in both cells (25.9 against 25.6, 26.4 against 26.2).

The raw output is in `bench-env/results/20261026-2334/` (git-ignored).

## Twelfth run: overnight, TPC-C scale 32 and YCSB at 1M rows, three levels, fast and slow (2026-10-28)

Run `20261028-0145-pg` from `bench-env/plans/overnight-postgresql.json` on main `db0bf06`, the
night's three items included. Those are Oracle's inline INSERT, the result-set fast path and the
lazy anchor map; the latter two touch PostgreSQL too.
- Workloads: TPC-C scale 32 and YCSB A, C, E, F at 1M rows.
- Cells: READ COMMITTED, SNAPSHOT and SERIALIZABLE, both layouts, the fast network and then 250 µs
  more a round trip.
- 60 s a run, REPS 2, 4 terminals, `shared_buffers` 8 GB, a client flight recording of every run.
- Its own stack (`STACK=pg`), beside Oracle's. All 12 cells passed.

Throughput, (c)/(a):

| cell | TPC-C | A | C | E | F |
|---|---|---|---|---|---|
| RC decoupled, fast | 0.71 | 0.81 | 0.89 | **0.54** | 0.81 |
| RC inline, fast | 0.78 | 0.87 | 0.92 | 0.87 | 0.88 |
| SNAPSHOT decoupled, fast | 0.79 | 0.84 | 0.91 | 0.83 | 0.86 |
| SNAPSHOT inline, fast | 0.87 | 0.90 | 0.89 | 0.79 | 0.89 |
| SERIALIZABLE decoupled, fast | 0.84 | 0.86 | 0.86 | 0.80 | 0.87 |
| SERIALIZABLE inline, fast | **0.96** | 0.92 | 0.86 | 0.78 | 0.94 |
| RC decoupled, +250 µs | 0.88 | 0.93 | 0.98 | **0.58** | 0.94 |
| RC inline, +250 µs | 0.89 | 0.95 | 0.98 | 0.84 | 0.96 |
| SNAPSHOT decoupled, +250 µs | 0.93 | 0.95 | 0.97 | 0.82 | 0.95 |
| SNAPSHOT inline, +250 µs | 0.93 | 0.97 | 0.97 | 0.82 | 0.97 |
| SERIALIZABLE decoupled, +250 µs | 0.86 | 0.95 | 0.97 | 0.82 | 0.94 |
| SERIALIZABLE inline, +250 µs | 0.93 | 0.97 | 0.97 | 0.82 | 0.99 |

YCSB p50 overhead, fast network:

| | read (C) | update | RMW | scan | insert |
|---|---|---|---|---|---|
| RC decoupled | +12% | +27% | +27% | **+88%** | +30% |
| RC inline | +8% | +21% | +17% | +16% | +22% |
| SNAPSHOT decoupled | +11% | +23% | +18% | +21% | +28% |
| SNAPSHOT inline | +12% | +8% | +9% | +25% | +20% |
| SERIALIZABLE decoupled | +15% | +21% | +17% | +26% | +41% |
| SERIALIZABLE inline | +16% | +7% | +4% | +30% | +18% |

- **TPC-C at scale 32** is 0.71–0.96 fast and 0.86–0.93 slow. The inline layout is ahead at every
  level, and SERIALIZABLE inline is at 0.96.
- **Inline meets 10–20%** for reads, updates and read-modify-write at SNAPSHOT and SERIALIZABLE.
  At READ COMMITTED inline, the update is +21%.
- **The READ COMMITTED decoupled scan** is the outlier, at +88% (YCSB E 0.54 fast, 0.58 slow). In
  pg_stat_statements per call, the driver's scan runs 1.68 ms of execution and 0.20 ms of planning,
  against the vendor's 0.65 and 0.04. At SNAPSHOT decoupled it is 0.87 and 0.23 against 0.71 and
  0.04. The execution is the metadata join for every scanned row, which READ COMMITTED's anchors
  need, as on Oracle. Every call is planned, on both sides.
- **At +250 µs** every YCSB cell but READ COMMITTED decoupled's E is 0.82–0.99.

## Thirteenth run: B1, the committed copy's key range on the metadata side (2026-10-28)

Run `20261028-1706-pg` from `bench-env/plans/targeted-postgresql.json` on main `99e9c86`. Among
the round's five fixes, B1 (`99e9c86`) is PostgreSQL's: the committed copy's outer join repeats
the predicate's key conjuncts in its ON clause for the metadata side, since PostgreSQL carries no
inequality across an outer join (todo 334, 966e3f3). YCSB E at 1M rows, READ COMMITTED, both
layouts, the fast network, 60 s × 2, 4 terminals. It ran beside Oracle's targeted run; about 15
minutes.

| YCSB E, RC | twelfth run | thirteenth run |
|---|---|---|
| decoupled, (c)/(a) | 0.54 | **0.71** |
| decoupled scan p50, vendor → driver | +88% | 1,314 → 1,870 µs (+42%) |
| decoupled scan, execution / planning a call | 1.68 / 0.20 ms (vendor 0.65 / 0.04) | **1.04** / 0.23 ms (vendor 0.67 / 0.04) |
| inline, (c)/(a) | 0.87 | 0.79 |
| inline scan p50 | +16% | 1,318 → 1,691 µs (+28%) |
| inline scan, execution / planning a call | — | 0.86 / 0.14 ms (vendor 0.69 / 0.04) |

- **The plan** is now a Merge Left Join of two index scans: the data table's primary key, and
  the metadata table's with the range in its Index Cond, where it was a Hash Right Join over a
  sequential scan of the metadata table. This is the generic plan in `server.txt`.
- **The plans shown are generic plans of pg_stat_statements' normalized texts**, not the plans
  that ran. Every call is custom-planned, on both sides (181,834 plans for the vendor's 181,830
  scans): the generic estimate for the range is 5,000 rows a side, ten times the custom plan's 500,
  so `plan_cache_mode=auto` never keeps the generic plan. The normalized texts also turn the
  driver's literal states into `$n`, so the in-flight arm cannot match the partial in-flight index
  there, as the real text can.
- **What remains** at RC decoupled is +0.37 ms of execution a scan and 0.19 ms of planning. Planning
  grows with the statement: 0.04 ms for the vendor, 0.14 inline, 0.23 decoupled. That is 0.10 of
  the inline gap's 0.27 ms a call, and 0.19 of the decoupled gap's 0.56. Whether the execution is
  the join's second index range scan and its heap reads (the layout's cost at RC, design 4.2) or
  the in-flight arm reading the range again needs the plans that run (todo 334).
- **The inline cell** has no change of its own in this round. Its 0.79 against 0.87 is the
  variation between two runs, here and in the vendor's own figures.

## Fourteenth run: overnight on main with D1–D4, three levels, fast and slow (2026-10-29)

Run `20261029-0022-pg` from `bench-env/plans/overnight-postgresql.json` on main `5de26b9`. D1–D3
are Oracle's and change nothing here, so this is the baseline after B1 (`99e9c86`). D4 adds the
plans of the ten statements of most time a call to `server.txt`.
- The first attempt (`20261028-2259-pg`, on `f5c4628`) failed every cell in the harness. D4's plan
  dump cut a statement's whitespace-collapsed text at the original text's length, which ran past its
  end (`IndexOutOfBoundsException` in `Server.plans`). `5de26b9` cuts the collapsed text; the stack
  was destroyed and the run restarted.
- Workloads: TPC-C scale 32 and YCSB A, C, E, F at 1M rows.
- Cells: READ COMMITTED, SNAPSHOT and SERIALIZABLE, both layouts, the fast network and then 250 µs
  more a round trip.
- 60 s a run, REPS 2, 4 terminals, `shared_buffers` 8 GB, a client flight recording of every run.
- Its own stack (`STACK=pg`), beside Oracle's. All 12 cells passed, 35–43 min each.

Throughput, (c)/(a) (twelfth run in brackets):

| cell | TPC-C | A | C | E | F |
|---|---|---|---|---|---|
| RC decoupled, fast | 0.70 (0.71) | 0.81 (0.81) | 0.87 (0.89) | **0.67** (0.54) | 0.81 (0.81) |
| RC inline, fast | 0.76 (0.78) | 0.85 (0.87) | 0.95 (0.92) | 0.84 (0.87) | 0.87 (0.88) |
| SNAPSHOT decoupled, fast | 0.80 (0.79) | 0.83 (0.84) | 0.88 (0.91) | 0.87 (0.83) | 0.81 (0.86) |
| SNAPSHOT inline, fast | 0.83 (0.87) | **0.91** (0.90) | 0.88 (0.89) | 0.83 (0.79) | **0.90** (0.89) |
| SERIALIZABLE decoupled, fast | 0.78 (0.84) | 0.83 (0.86) | 0.88 (0.86) | 0.80 (0.80) | 0.85 (0.87) |
| SERIALIZABLE inline, fast | **0.96** (0.96) | 0.88 (0.92) | 0.86 (0.86) | 0.76 (0.78) | **0.90** (0.94) |
| RC decoupled, +250 µs | 0.87 (0.88) | **0.93** (0.93) | **0.97** (0.98) | **0.70** (0.58) | **0.94** (0.94) |
| RC inline, +250 µs | 0.89 (0.89) | **0.95** (0.95) | **0.98** (0.98) | 0.78 (0.84) | **0.96** (0.96) |
| SNAPSHOT decoupled, +250 µs | **0.91** (0.93) | **0.94** (0.95) | **0.96** (0.97) | 0.81 (0.82) | **0.95** (0.95) |
| SNAPSHOT inline, +250 µs | **0.94** (0.93) | **0.95** (0.97) | **0.98** (0.97) | 0.80 (0.82) | **0.97** (0.97) |
| SERIALIZABLE decoupled, +250 µs | **0.97** (0.86) | **0.93** (0.95) | **0.97** (0.97) | 0.84 (0.82) | **0.96** (0.94) |
| SERIALIZABLE inline, +250 µs | **0.96** (0.93) | **0.95** (0.97) | **0.96** (0.97) | 0.82 (0.82) | **0.98** (0.99) |

p50 overhead against the vendor, fast network:

| | Delivery | NewOrder | OrderStatus | Payment | StockLevel | read (C) | update | RMW | scan | insert |
|---|---|---|---|---|---|---|---|---|---|---|
| RC decoupled | +79% | +34% | +41% | +43% | +54% | +13% | +29% | +26% | **+50%** | +31% |
| RC inline | +63% | +24% | +28% | +36% | +48% | +6% | +20% | +18% | +20% | +11% |
| SNAPSHOT decoupled | +38% | +20% | +36% | +30% | +9% | +14% | +24% | +21% | +14% | +28% |
| SNAPSHOT inline | +13% | +18% | +42% | +19% | +45% | +13% | +7% | +6% | +21% | +20% |
| SERIALIZABLE decoupled | +40% | +21% | +38% | +31% | +11% | +13% | +22% | +20% | +26% | +30% |
| SERIALIZABLE inline | +10% | +10% | +37% | +9% | +34% | +16% | +8% | +7% | +32% | +29% |

- **The same as the twelfth run within the variation between runs**, as expected, with one change:
  the READ COMMITTED decoupled scan is +50% (+88% in the twelfth run), YCSB E 0.67 fast and 0.70
  slow (0.54 and 0.58). That is B1, as in the thirteenth run (0.71).
- **At +250 µs, every cell's YCSB A, C and F is within 10%** (0.93–0.98), and TPC-C is within 10%
  at SNAPSHOT and SERIALIZABLE (0.91–0.97). There, TPC-C transactions are +3% to +13% except
  StockLevel. Reads, updates and read-modify-write are +1% to +9% at every level.
- **The scan (YCSB E) is the gap left** at every level: +14% to +32% fast except RC decoupled's
  +50%, and +20% to +44% at +250 µs. Its execution and per-call planning are in the thirteenth run.
- **StockLevel on the inline layout** is +34% to +48% fast and +26% to +40% at +250 µs, where the
  decoupled layout is +9% to +11% at SNAPSHOT and SERIALIZABLE.
- **SERIALIZABLE TPC-C at 0.96–0.97** compares against a vendor that also runs SERIALIZABLE, whose
  own serialization failures lower (a). Its p50 overheads, +9% to +10% for Delivery, NewOrder and
  Payment inline, are the closer measure.

## Reports

### HEAD 93f5217

#### tpcc

Run (c): imported customer, district, item, new_order, oorder, order_line, stock, warehouse; plain history.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 527.9 | 1.00 | 529.3 | 3512 | 15233 | 27252 |
| (c) | 403.4 | 0.76 | 403.2 | 4522 | 20605 | 40640 |

| run | commits | active ms/commit | idle in transaction ms/commit | WAL KB/commit | server CPU ms/commit | server packets/commit |
|---|---|---|---|---|---|---|
| (a) | 64151 | 0.369 | 6.577 | 8.3 | 0.899 | 19.7 |
| (c) | 49024 | 0.902 | 6.951 | 19.1 | 2.821 | 20.2 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| Delivery | (a) | 7799 | 0 | 1 | 0 | 26866 | 27768 | 1.00 |
| Delivery | (c) | 5858 | 0 | 2 | 0 | 39367 | 42278 | 1.52 |
| NewOrder | (a) | 86202 | 896 | 20 | 0 | 11137 | 14977 | 1.00 |
| NewOrder | (c) | 65408 | 652 | 26 | 0 | 14096 | 19374 | 1.29 |
| OrderStatus | (a) | 7761 | 0 | 0 | 0 | 1527 | 1626 | 1.00 |
| OrderStatus | (c) | 5844 | 0 | 0 | 0 | 1690 | 1960 | 1.21 |
| Payment | (a) | 83107 | 0 | 2 | 0 | 3077 | 3459 | 1.00 |
| Payment | (c) | 62819 | 0 | 0 | 0 | 3707 | 4175 | 1.21 |
| StockLevel | (a) | 7753 | 0 | 0 | 0 | 2114 | 2350 | 1.00 |
| StockLevel | (c) | 5829 | 0 | 0 | 0 | 3963 | 4706 | 2.00 |

#### ycsb

Run (c): imported usertable; plain none.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 4026.2 | 1.00 | 4057.9 | 778 | 2086 | 2662 |
| (c) | 3712.4 | 0.92 | 3738.1 | 828 | 2427 | 3125 |

| run | commits | active ms/commit | idle in transaction ms/commit | WAL KB/commit | server CPU ms/commit | server packets/commit |
|---|---|---|---|---|---|---|
| (a) | 491059 | 0.159 | 0.458 | 0.4 | 0.186 | 11.8 |
| (c) | 452420 | 0.205 | 0.480 | 0.5 | 0.256 | 11.8 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| DeleteRecord | (a) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| DeleteRecord | (c) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| InsertRecord | (a) | 73754 | 0 | 0 | 0 | 793 | 859 | 1.00 |
| InsertRecord | (c) | 67540 | 0 | 0 | 0 | 855 | 933 | 1.09 |
| ReadModifyWriteRecord | (a) | 296029 | 0 | 5264 | 0 | 1173 | 1271 | 1.00 |
| ReadModifyWriteRecord | (c) | 270653 | 0 | 5137 | 0 | 1320 | 1460 | 1.15 |
| ReadRecord | (a) | 736576 | 0 | 0 | 0 | 724 | 782 | 1.00 |
| ReadRecord | (c) | 676620 | 0 | 0 | 0 | 747 | 805 | 1.03 |
| ScanRecord | (a) | 221761 | 0 | 0 | 0 | 1700 | 2692 | 1.00 |
| ScanRecord | (c) | 203225 | 0 | 0 | 0 | 1948 | 3205 | 1.19 |
| UpdateRecord | (a) | 147061 | 0 | 2561 | 0 | 797 | 876 | 1.00 |
| UpdateRecord | (c) | 135578 | 0 | 2753 | 0 | 914 | 1026 | 1.17 |

### Baseline ef77549

#### tpcc

Run (c): imported customer, district, item, new_order, oorder, order_line, stock, warehouse; plain history.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 524.7 | 1.00 | 526.5 | 3561 | 15379 | 27416 |
| (c) | 308.9 | 0.59 | 309.0 | 4712 | 30564 | 41929 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| Delivery | (a) | 7606 | 0 | 0 | 0 | 26975 | 28205 | 1.00 |
| Delivery | (c) | 4559 | 0 | 2 | 0 | 40489 | 43456 | 1.54 |
| NewOrder | (a) | 84709 | 864 | 15 | 0 | 11230 | 15068 | 1.00 |
| NewOrder | (c) | 49955 | 509 | 19 | 0 | 20765 | 29424 | 1.95 |
| OrderStatus | (a) | 7432 | 0 | 0 | 0 | 1528 | 1645 | 1.00 |
| OrderStatus | (c) | 4339 | 0 | 0 | 0 | 1849 | 2426 | 1.47 |
| Payment | (a) | 82143 | 0 | 0 | 0 | 3098 | 3471 | 1.00 |
| Payment | (c) | 48334 | 0 | 0 | 0 | 3836 | 4340 | 1.25 |
| StockLevel | (a) | 7471 | 0 | 0 | 0 | 2170 | 2445 | 1.00 |
| StockLevel | (c) | 4513 | 0 | 0 | 0 | 4189 | 4898 | 2.00 |

#### ycsb

Run (c): imported usertable; plain none.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 4066.6 | 1.00 | 4098.5 | 770 | 2071 | 2650 |
| (c) | 2834.2 | 0.70 | 2855.4 | 831 | 5298 | 7299 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| DeleteRecord | (a) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| DeleteRecord | (c) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| InsertRecord | (a) | 74679 | 0 | 0 | 0 | 788 | 853 | 1.00 |
| InsertRecord | (c) | 51562 | 0 | 0 | 0 | 842 | 923 | 1.08 |
| ReadModifyWriteRecord | (a) | 297515 | 0 | 5361 | 0 | 1166 | 1262 | 1.00 |
| ReadModifyWriteRecord | (c) | 206591 | 0 | 3027 | 0 | 1310 | 1448 | 1.15 |
| ReadRecord | (a) | 741831 | 0 | 0 | 0 | 720 | 777 | 1.00 |
| ReadRecord | (c) | 516347 | 0 | 0 | 0 | 744 | 823 | 1.06 |
| ScanRecord | (a) | 223286 | 0 | 0 | 0 | 1688 | 2684 | 1.00 |
| ScanRecord | (c) | 154973 | 0 | 0 | 0 | 4152 | 7501 | 2.79 |
| UpdateRecord | (a) | 148769 | 0 | 2758 | 0 | 793 | 871 | 1.00 |
| UpdateRecord | (c) | 103803 | 0 | 1649 | 0 | 900 | 1001 | 1.15 |

### Second run, HEAD c7f0e5b

#### tpcc

Run (c): imported customer, district, item, new_order, oorder, order_line, stock, warehouse; plain history.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 711.5 | 1.00 | 713.9 | 2618 | 11388 | 20150 |
| (b) | 704.6 | 0.99 | 705.2 | 2660 | 11490 | 20333 |
| (c) | 506.5 | 0.71 | 505.5 | 3871 | 16595 | 32683 |

| run | commits | active ms/commit | idle in transaction ms/commit | WAL KB/commit | server CPU ms/commit | server packets/commit |
|---|---|---|---|---|---|---|
| (a) | 86489 | 0.399 | 4.656 | 8.1 | 0.956 | 19.7 |
| (b) | 85487 | 0.382 | 4.737 | 7.8 | 0.936 | 19.7 |
| (c) | 61403 | 0.913 | 5.034 | 18.7 | 2.842 | 20.1 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| Delivery | (a) | 6902 | 0 | 0 | 0 | 19718 | 20696 | 1.00 |
| Delivery | (b) | 6796 | 0 | 0 | 0 | 19854 | 21057 | 1.02 |
| Delivery | (c) | 4763 | 0 | 0 | 0 | 31059 | 34220 | 1.65 |
| NewOrder | (a) | 77400 | 759 | 21 | 0 | 8247 | 11122 | 1.00 |
| NewOrder | (b) | 76521 | 792 | 22 | 0 | 8332 | 11222 | 1.01 |
| NewOrder | (c) | 54797 | 539 | 11 | 0 | 11366 | 15831 | 1.42 |
| OrderStatus | (a) | 6827 | 0 | 0 | 0 | 1117 | 1219 | 1.00 |
| OrderStatus | (b) | 6817 | 0 | 0 | 0 | 1125 | 1263 | 1.04 |
| OrderStatus | (c) | 4878 | 0 | 0 | 0 | 1283 | 1510 | 1.24 |
| Payment | (a) | 74606 | 0 | 0 | 0 | 2277 | 2550 | 1.00 |
| Payment | (b) | 73584 | 0 | 0 | 0 | 2287 | 2574 | 1.01 |
| Payment | (c) | 53024 | 0 | 0 | 0 | 2935 | 3331 | 1.31 |
| StockLevel | (a) | 7030 | 0 | 0 | 0 | 1893 | 2151 | 1.00 |
| StockLevel | (b) | 6936 | 0 | 0 | 0 | 1877 | 2152 | 1.00 |
| StockLevel | (c) | 4868 | 0 | 0 | 0 | 3498 | 4322 | 2.01 |

#### ycsb

Run (c): imported usertable; plain none.

| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |
|---|---|---|---|---|---|---|
| (a) | 5026.1 | 1.00 | 5065.8 | 583 | 2012 | 2746 |
| (b) | 4953.6 | 0.99 | 4989.3 | 587 | 2105 | 2781 |
| (c) | 4475.1 | 0.89 | 4505.9 | 639 | 2332 | 3166 |

| run | commits | active ms/commit | idle in transaction ms/commit | WAL KB/commit | server CPU ms/commit | server packets/commit |
|---|---|---|---|---|---|---|
| (a) | 613018 | 0.175 | 0.346 | 0.4 | 0.202 | 11.8 |
| (b) | 603790 | 0.172 | 0.358 | 0.4 | 0.199 | 11.8 |
| (c) | 545326 | 0.223 | 0.370 | 0.5 | 0.274 | 11.7 |

| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs | p95 vs (a) |
|---|---|---|---|---|---|---|---|---|
| DeleteRecord | (a) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| DeleteRecord | (b) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| DeleteRecord | (c) | 0 | 0 | 0 | 0 | 0 | 0 |  |
| InsertRecord | (a) | 61232 | 0 | 0 | 0 | 602 | 671 | 1.00 |
| InsertRecord | (b) | 60535 | 0 | 0 | 0 | 605 | 672 | 1.00 |
| InsertRecord | (c) | 54383 | 0 | 0 | 0 | 668 | 746 | 1.11 |
| ReadModifyWriteRecord | (a) | 244573 | 0 | 4228 | 0 | 885 | 986 | 1.00 |
| ReadModifyWriteRecord | (b) | 242260 | 0 | 4164 | 0 | 890 | 990 | 1.00 |
| ReadModifyWriteRecord | (c) | 218438 | 0 | 4131 | 0 | 1041 | 1192 | 1.21 |
| ReadRecord | (a) | 612998 | 0 | 0 | 0 | 534 | 589 | 1.00 |
| ReadRecord | (b) | 603233 | 0 | 0 | 0 | 536 | 591 | 1.00 |
| ReadRecord | (c) | 545549 | 0 | 0 | 0 | 555 | 612 | 1.04 |
| ScanRecord | (a) | 183716 | 0 | 0 | 0 | 1623 | 2823 | 1.00 |
| ScanRecord | (b) | 181052 | 0 | 0 | 0 | 1676 | 2866 | 1.02 |
| ScanRecord | (c) | 162689 | 0 | 0 | 0 | 1861 | 3267 | 1.16 |
| UpdateRecord | (a) | 123411 | 0 | 2127 | 0 | 606 | 687 | 1.00 |
| UpdateRecord | (b) | 120338 | 0 | 2053 | 0 | 609 | 687 | 1.00 |
| UpdateRecord | (c) | 109377 | 0 | 2210 | 0 | 730 | 853 | 1.24 |
