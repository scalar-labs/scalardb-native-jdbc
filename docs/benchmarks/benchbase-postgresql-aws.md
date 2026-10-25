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
