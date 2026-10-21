---
status: pending
priority: p1
issue_id: "305"
tags: [code-review, testing, write, mysql, mariadb]
dependencies: ["304"]
---

# The write corpus's table check stops at the first table whose types differ: o and k never compared on MySQL and MariaDB

## Problem Statement

`WriteCorpusIntegrationTest.sameTables` returns `"pass, types of ..."` as soon as one table's
values match but its column types differ, without comparing the tables after it. On MySQL and
MariaDB the first table, `c`, always differs in type (`vip` TINYINT against BIT, todo 304), so
after every statement, and at the end, only `c` is compared through the driver; `o` and `k`
never are. A driver that writes a wrong value or a wrong row of `o` or `k` (including a row in
flight it should not touch) passes every per-statement check there. The end check reads them through
ScalarDB, but only after the held transactions are rolled back, which restores the before images of
rows in flight, and only the final state: a divergence a later statement erases, or one in a
prepared record that rollback restores, is never seen.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:345-362`: the loop over `c, o, k` returns from inside at
  `if (!types.isEmpty()) return "pass" + types;` (359-361) after the first table.
- Todo 304: every table comparison of `c` on MySQL and MariaDB reports `[vip:-6]` against
  `[vip:-7]`, so the early return fires on every call on those engines.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:234-236`: the end check calls the same method; `scalarDbAgrees`
  runs after it on all three tables, but after `tx.rollback()` of the held transactions (225-227).
- Same method, 352-354: `got.columns` is replaced by `want.columns`, labels included, and
  `CorpusIntegrationTest.sameRow` (CorpusIntegrationTest.java:382-398) iterates the expected
  row's width only. A driver `SELECT *` that returns extra trailing columns (say `tx_` metadata
  leaking on the inline layout) or other labels still passes, reported only as a type difference.

## Proposed Solutions

### Option 1: collect the type differences, compare every table
Keep a `StringBuilder` of type differences across the loop and return `"pass" + types` after it.
Also compare the column count and labels (only the JDBC type set apart). **Effort:** Small.

## Acceptance Criteria

- [ ] `sameTables` compares all three tables whatever the types of the first.
- [ ] A driver result with more columns or other labels than the twin's fails the table check.
- [ ] Rerun on MySQL and MariaDB; any `o`/`k` divergence found gets its own todo.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code
- Read `sameTables` against todo 304's finding.

## Resources

- docs/plans/testing.md, milestone T3; todo 304.
