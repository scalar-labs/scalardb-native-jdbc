---
status: pending
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

- [ ] A read-only SERIALIZABLE read works on MySQL and MariaDB, or the refusal names the reason

## Work Log

- 2026-10-20: found by SpringDataSampleIntegrationTest (9c); the sample's SERIALIZABLE read is
  read-write, and the README says so.
