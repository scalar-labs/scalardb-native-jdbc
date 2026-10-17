---
status: pending
priority: p3
issue_id: "199"
tags: [code-review, investigation, mysql, mariadb]
dependencies: []
---

# Milestone 8a suspicions the review could not confirm

## Problem Statement

To check, not yet reproduced:

- The FOR SHARE relock adds NOWAIT under SKIP LOCKED: on a covering index a row whose primary key
  another transaction holds fails with 3572 instead of being skipped (ponytail in Router).
- The relock takes S locks on the metadata rows too (the LEFT JOIN is under the clause): could make a
  ScalarDB prepare wait, or fail under its own NOWAIT-like settings.
- `keyViolated` with a localized `lc_messages`: no "for key '" in the message, so any 1062 is taken as
  the primary key's; a secondary-unique duplicate could then abort spuriously when a key is in flight.
- After a MySQL deadlock (1213) inside an explicit transaction the vendor transaction is gone but the
  driver's anchors and own stamps stay until the application rolls back.
- Connector/J computes multi-row generated keys from the `auto_increment_increment` cached at connect.
- A locking read ending in `;` may be refused by the tail comparison.

## Acceptance Criteria

- [ ] Each item reproduced and filed, or ruled out

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
