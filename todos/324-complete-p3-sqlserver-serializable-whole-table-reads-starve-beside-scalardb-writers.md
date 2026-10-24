---
status: complete
priority: p3
issue_id: "324"
tags: [isolation, liveness, sqlserver, serializable]
dependencies: []
---

# On SQL Server at SERIALIZABLE, whole-table reads of a hot table almost never commit while ScalarDB writes it

## Problem Statement

The T5 bank test (`isolation/BankIntegrationTest`: 12 accounts, two driver and two ScalarDB
writers, two driver readers of `SUM`, `GROUP BY` and every row) commits almost nothing on SQL
Server at SERIALIZABLE with ScalarDB writing: in 60 seconds, 12 to 51 transfers and 0 to 4 reads of
about 500 to 3,100 tried. The totals hold; the throughput does not. The same run without ScalarDB
writers commits about 500 transfers and 350 reads, and SQL Server at SNAPSHOT with them about 1,300
transfers and 5,000 reads.

## Findings

- 2026-10-23, the first failure of each kind kept by the test:
  - reads: `40001` "a table the read reaches has a row a ScalarDB transaction is writing" (design
    section 6: at SNAPSHOT and SERIALIZABLE a row in flight aborts, it does not wait), and SQL
    Server's deadlock victim (1205) between the read's key-range locks and ScalarDB's prepare;
  - driver writers: 1205, and `40001` "a row the statement locks changed after the transaction's
    snapshot";
  - ScalarDB: prepare conflicts and reads failing as deadlock victims.
- A read of the whole table meets one of the in-flight rows of ScalarDB's two writers nearly every
  time; the other engines at SERIALIZABLE read about 1,200 to 9,000 times in the same test.
- Not a correctness issue: every total held, on every engine, level and layout.
- 2026-10-23, the membership test (`isolation/MembershipIntegrationTest`), whose writers scan a group
  (`WHERE grp = ?`, a whole-table scan for both sides) before they insert or delete: at SERIALIZABLE
  with ScalarDB writing, 60 seconds committed 9 to 23 writes in all and 7 to 24 reads, against 465
  to 520 aborted reads; the writers spent the run mostly waiting. The same test without ScalarDB's
  writers committed about 300 writes and 2,300 reads (decoupled), and every other engine thousands.
  Deadlock victims (1205) on both sides, ScalarDB's scans and validations among them, and the
  driver's in-flight `40001`. The rules and the count held. So the starvation reaches writers that
  read by predicate, not only readers of the whole table: a scan-then-write workload hardly runs.

## Proposed Solutions

### Option 1: document it as a limit
SERIALIZABLE on SQL Server beside ScalarDB writers suits reads of a few keys, not scans of a table
ScalarDB writes all the time. **Effort:** Small. **Risk:** none.

### Option 2: wait for an in-flight row at a read on SQL Server's lock-based SERIALIZABLE
Unlike a fixed snapshot, a lock-based read sees the row once it settles. But the wait would hold
the range locks already taken, which ScalarDB's commit of the row may need (the reason section 6
bounds every wait). **Effort:** Medium. **Risk:** stalls; needs the bounded wait of
`inflightWaitMs` and a measurement.

## Recommended Action

For readers, a read-only transaction at SERIALIZABLE reads without locks (built 2026-10-24); for
the scanning writers, Option 1, with todo 326 to look again after the predicate checks.

## Acceptance Criteria

- [x] The decision recorded in design section 6 or 7, or the wait built and the bank test's reads
      at SQL Server SERIALIZABLE with ScalarDB writers above zero: read-only readers built (design
      5), the bank test's reads 0 to thousands; the scanning writers documented (design 7), and
      followed in todo 326.

## Work Log

### 2026-10-23 - found by the T5 bank test
- 20 and 60 seconds a run, both layouts; the test checks no read count for this case.

### 2026-10-23 - the membership test too
- The test checks no progress for this case. Worth re-triaging: a scan-then-write workload at
  SERIALIZABLE on SQL Server beside ScalarDB writers is close to stopped, which may be more than P3.

### 2026-10-24 - readers fixed: a read-only transaction reads without locks
- At SERIALIZABLE on MySQL, MariaDB and SQL Server a transaction on a read-only connection runs as
  at SNAPSHOT, without the locking re-reads; the driver tracks `setReadOnly` (mssql-jdbc ignores
  it) and refuses anything but reads in it. The argument is in design 5: the writers fit one
  timeline (a driver writer at its commit, a ScalarDB one at its prepare), and a snapshot that
  aborts on rows in flight is a cut of it. Not on PostgreSQL (SSI).
- Checked: `ReadOnlyReaderIntegrationTest` (Fekete's read-only anomaly at five timings, every read
  path, a READ COMMITTED control that shows it), Elle list-append with the driver's read-only
  transactions so run (all serializable; the READ COMMITTED control invalid), and the bank test:
  reads a minute on SQL Server with ScalarDB writing 0 to 8,506-20,200; MySQL and MariaDB 4-12x
  (measured with the built driver; an earlier run that set the readers' level directly gave
  8,670-22,104 on SQL Server and 3-8x elsewhere).
- The writers that scan before they write (the membership test) still starve on SQL Server at
  SERIALIZABLE beside ScalarDB: documented as a limit (design 7, README), and moved to todo 326,
  to look at again once the predicate checks of another branch land.
