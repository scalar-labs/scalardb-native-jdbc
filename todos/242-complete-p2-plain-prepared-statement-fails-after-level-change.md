---
status: complete
priority: p2
issue_id: "242"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# A plain PreparedStatement fails after the level changes

## Problem Statement

Plain routes now differ by level but carry parameterSources == null, so canPrepareAgain
(Proxies.java:1148) is false and the execute fails at Proxies.java:693 with 0AN01 "prepared at another
isolation level ... prepare it again". Probe: prepare `SELECT v FROM p WHERE id = ?` at READ COMMITTED,
setTransactionIsolation(SERIALIZABLE), execute; both engines. Worked before 77e2295; statement-caching
pools hit it.

## Proposed Solutions

### Option 1
Give plain routes identity parameter sources, or allow preparing again when there are no splits or anchors.

## Acceptance Criteria

- [ ] The probe executes on both engines; a test

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
- 2026-10-18: fixed: plain SELECTs on MySQL/MariaDB are kept for preparing again, their parameters recorded and replayed (null sources forwarded); not with a stream bound; tested.
