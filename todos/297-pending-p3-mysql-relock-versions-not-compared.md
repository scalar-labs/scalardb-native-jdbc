---
status: pending
priority: p3
issue_id: "297"
tags: [code-review, mysql, locking, verify]
dependencies: []
---

# MySQL/MariaDB: the versions read under the relock are not compared with the locking read's

## Problem Statement

Writes.LockPlan.lockedVersions relocks by primary key (FORCE INDEX (PRIMARY) ... FOR SHARE) and reads
the versions there, but on MySQL and MariaDB does not compare them with the versions the locking
statement returned; only SQL Server's relockedAsRead does. On a decoupled table with a truly covering
secondary index, the first statement locks the index entry only, and a ScalarDB commit might slip in
between the two statements: the user sees the old row while the transaction holds the lock on the new
version. Not verified; on the inline layout the clustered row is locked by the first statement
(measured in the review of 10c).

## Proposed Solutions

1. Verify with a covering index on a decoupled table and a ScalarDB commit between the two statements
   (a pause function in the locking read); compare the versions as SQL Server does if it slips in.

## Recommended Action

## Acceptance Criteria

- [ ] Done

## Work Log

- 2026-10-20: found in the review of 10c (6bce052).
