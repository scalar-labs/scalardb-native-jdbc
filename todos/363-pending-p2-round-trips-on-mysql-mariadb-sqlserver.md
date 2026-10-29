---
status: pending
priority: p2
issue_id: "363"
tags: [performance, round-trips, mysql, mariadb, sqlserver]
dependencies: []
---

# Round trips of a write on MySQL, MariaDB and SQL Server

## Problem Statement

Todo 328 made a write one round trip on PostgreSQL and Oracle, and packets per commit equal the
vendor's on both in the AWS runs. Its last item, the other engines, was never measured: the
harness's BenchBase runs only PostgreSQL and Oracle (`BenchBaseBenchmark.benchBase`'s assumption).
On MySQL, MariaDB and SQL Server a keyed write, a batch and a locking read may still be several
statements a round trip each.

## Proposed Solution

1. Count the statements and round trips a write, a batch entry and a locking read take on each of
   the three engines on the containers, beside the vendor driver's.
2. Where they are more than one, the forms todo 329's plan (step 6) named: a multi-statement batch
   or a multi-table UPDATE on MySQL and MariaDB, one T-SQL batch on SQL Server.
3. BenchBase on the three engines in the harness, for (a) against (c).

## Acceptance Criteria

- [ ] The counts per operation and engine, against the vendor's.
- [ ] A decision with the user on which engines get forms of their own.

## Work Log

- 2026-10-29: split from todo 328 when it closed (the user's go-ahead).
