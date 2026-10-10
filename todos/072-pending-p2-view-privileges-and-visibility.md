---
status: pending
priority: p2
issue_id: "072"
tags: [code-review, security, setup, design]
dependencies: []
---

# View privileges and visibility

## Problem Statement

The setup grants nothing and creates views that run with the creator's rights, so application users either cannot read `rc_T`, or read it with the setup user's rights, bypassing row-level security and column grants on `T`; and catalog listings may hide the objects from the application user, who is then told to run a setup that already ran.

## Findings

- Definer rights: PostgreSQL views run as the owner (RLS evaluated as the owner) unless `security_invoker = true` (15+); MySQL/MariaDB `SQL SECURITY DEFINER` with the setup user as DEFINER; Oracle definer rights; SQL Server ownership chaining (security F4).
- MySQL `information_schema`, Oracle `ALL_*` and SQL Server `sys.*` list only objects the caller has privileges on; the tests run as one user and never see this (architecture).
- The 55000 message says "run the setup" even when the listing failed or the objects are invisible.

## Proposed Solutions

### Option 1: Invoker rights and a grantee

**Approach:** PostgreSQL 15+ `WITH (security_invoker = true)`; MySQL/MariaDB `SQL SECURITY INVOKER`; SQL Server and Oracle: document, or create under the table owner. `Setup` takes the application role and grants SELECT on `rc_T`. The message says "missing or not visible to this user".

**Pros:** Keeps the table's own privileges in force.

**Cons:** PostgreSQL before 15 has no invoker views; document.

**Effort:** Medium

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [ ] Design section 3 states the privilege model per engine.
- [ ] An integration test with a second, unprivileged application user on at least PostgreSQL and MySQL.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Reproduced: PostgreSQL 16 with an RLS policy on the table, the app role sees 1 row through the table and 2 through the view. MySQL 8.4: an app user granted SELECT on the data table only sees 0 views and not the in-flight index in information_schema, so the driver would report the table not set up after the setup ran.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
