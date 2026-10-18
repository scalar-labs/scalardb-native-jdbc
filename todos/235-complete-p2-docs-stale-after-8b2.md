---
status: complete
priority: p2
issue_id: "235"
tags: [code-review, docs]
dependencies: []
---

# Docs stale or contradictory after 8b2

## Problem Statement

- README.md:52-54 says ScalarDB needs scalar.db.jdbc.isolation_level=SERIALIZABLE where the driver runs
  SERIALIZABLE; on MySQL/MariaDB only consensus_commit is needed (tests run storage RR only).
- README.md:86-87 "Without both ScalarDB settings" reads as applying to MySQL: "the settings in its row".
- README.md:100-101: the coarse path aborts on any in-flight row of the table, not only rows the WHERE matches.
- README missing limits: a ScalarDB prepare blocked by a driver read waits innodb_lock_wait_timeout (50s)
  then fails as a conflict; a coarse read stalls every ScalarDB writer of the table until commit.
- design.md:300 (LOCK IN SHARE MODE "on both engines"; MySQL relock uses FOR SHARE), 306 (first statement,
  through rc_T), 1553-1555 (through rc_T) contradict the as-built paragraph (1450-1465).
- Router.java:568-572 lockable javadoc still says READ COMMITTED only.

## Proposed Solutions

### Option 1
Fix each item; point stale design text at section 5.

## Acceptance Criteria

- [ ] README and design match the code

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: fixed: README and design.md updated, lockable javadoc fixed.
