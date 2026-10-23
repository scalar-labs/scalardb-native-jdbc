---
status: pending
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

## Acceptance Criteria

- [ ] The decision recorded in design section 6 or 7, or the wait built and the bank test's reads
      at SQL Server SERIALIZABLE with ScalarDB writers above zero.

## Work Log

### 2026-10-23 - found by the T5 bank test
- 20 and 60 seconds a run, both layouts; the test checks no read count for this case.
