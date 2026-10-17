---
status: pending
priority: p3
issue_id: "197"
tags: [code-review, documentation]
dependencies: []
---

# Design text left stale by milestone 8a

## Problem Statement

- design.md ~1016-1030 (locking clause rule) still says a user FOR UPDATE/FOR SHARE is rewritten onto
  the base tables and that MySQL/MariaDB lock T_meta by key as in the SERIALIZABLE bullet.
- design.md ~1676 (anomaly checklist, covering index): "the locking re-read joins T_meta"; at READ
  COMMITTED it is now the re-lock under the versions read.
- The 08004 row of the error table does not list the useAffectedRows refusal.
- design.md :301 and :1067 ("do not use INSERT IGNORE") should point to 4.3's note that only the
  driver's own metadata rows go through INSERT IGNORE.
- Section 5's "Built so far" should say writes and locking reads at READ COMMITTED run on MySQL and
  MariaDB too.

## Acceptance Criteria

- [ ] Each passage matches the code

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
