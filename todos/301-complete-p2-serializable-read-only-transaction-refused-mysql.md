---
status: complete
priority: p2
issue_id: "301"
tags: [mysql, mariadb, serializable, read-only]
dependencies: []
---

# MySQL/MariaDB: a SERIALIZABLE read in a read-only transaction is refused

## Problem Statement

At SERIALIZABLE on MySQL and MariaDB every read of a ScalarDB table is a locking read (design 5), which
the driver's read-only guard (todo 160, 25006 "a read-only connection cannot write a ScalarDB table")
refuses. So Spring's `@Transactional(isolation = SERIALIZABLE, readOnly = true)` fails on every read.
Found by the 9c Spring Data sample; documented in the README as a limitation for the release.

## Proposed Solutions

1. Let locking reads through in a read-only transaction where the engine allows them (MySQL and
   MariaDB permit locking reads in READ ONLY transactions? to measure), keeping writes refused; the
   locks only order the read, they record nothing a commit needs (medium).

## Acceptance Criteria

- [x] A read-only SERIALIZABLE read works on MySQL and MariaDB, or the refusal names the reason

## Work Log

- 2026-10-20: found by SpringDataSampleIntegrationTest (9c); the sample's SERIALIZABLE read is
  read-write, and the README says so.
- 2026-10-20: measured on m8-mysql and m8-mariadb: in a READ ONLY transaction a shared locking read
  (FOR SHARE, LOCK IN SHARE MODE) runs and holds off a writer; FOR UPDATE fails with 1792 (25006).
  Fixed: the read-only guard applies to plans that write; a locking read (LockPlan) writes nothing
  and passes. The Spring sample's SERIALIZABLE read is read-only again, on every engine.
- 2026-10-20: partly: with the guard on writing plans only, MariaDB, PostgreSQL and SQL Server run a
  read-only SERIALIZABLE read (the Spring sample, mutation of readOnly). MySQL fails in Connector/J:
  setSavepoint sends SAVEPOINT through executeUpdate, refused on a read-only connection, and every
  plan runs under savepoints. The driver now refuses it on MySQL itself (25006, naming the cause);
  README says so. Open: savepoints as SQL (SAVEPOINT / ROLLBACK TO / RELEASE through execute) on
  MySQL in read-only transactions.
- 2026-10-24: done with todo 324: a read-only transaction at SERIALIZABLE on MySQL, MariaDB and SQL
  Server reads without the locking re-reads, so without their savepoints, and Connector/J runs it.
  A user's own locking read (`FOR SHARE`) in a read-only transaction on MySQL is still refused
  with 25006 for the savepoints; README's limitation is replaced by the read-only rule.
