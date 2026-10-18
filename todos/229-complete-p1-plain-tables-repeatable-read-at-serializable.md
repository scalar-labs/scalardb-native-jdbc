---
status: complete
priority: p1
issue_id: "229"
tags: [code-review, correctness, mysql, mariadb, serializable]
dependencies: []
---

# Plain tables get REPEATABLE READ under a reported SERIALIZABLE

## Problem Statement

Proxies.setTransactionIsolation runs the vendor at REPEATABLE READ; Router.decide (~L484) forwards a
statement with no ScalarDB table unchanged, so it is a plain snapshot read. Two driver transactions at
SERIALIZABLE write-skew on an ordinary table; plain-table subqueries inside coarse reads are unlocked too.
Regression: before cb4844f MySQL/MariaDB SERIALIZABLE was refused, and a passthrough level gave native
SERIALIZABLE. Verified by probe on both engines: pz.n (1,1),(2,1); d1 and d2 read SUM(v)=2, set id 1 and
id 2 to 0, both commit; sum 0 while getTransactionIsolation() returns 8.
Related: the coarse LOCK IN SHARE MODE also locks the plain tables of a join (MySQL could use FOR SHARE OF).

## Proposed Solutions

### Option 1
Run the vendor at its own SERIALIZABLE, keeping the driver's re-reads on top (passthrough already does this, see 232); check it doesn't add deadlocks on the data/meta tables.

### Option 2
Append LOCK IN SHARE MODE to plain-table SELECTs at this level (misses subqueries).

### Option 3
Document that plain tables run at SNAPSHOT under the driver's SERIALIZABLE.

## Acceptance Criteria

- [ ] Write skew between two driver transactions on a plain table is prevented at SERIALIZABLE on MySQL and MariaDB, or the README says it is not

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: fixed: locks every SELECT block (subqueries, derived tables, CTEs) with LOCK IN SHARE MODE at SERIALIZABLE on MySQL/MariaDB, plain tables too (SqlAst.lockingEverySelect); an unparenthesized set operation is refused. Verified on both engines that a clause per block locks each block's rows.
