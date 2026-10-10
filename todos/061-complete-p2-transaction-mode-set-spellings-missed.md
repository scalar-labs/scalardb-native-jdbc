---
status: complete
priority: p2
issue_id: "061"
tags: [code-review, security, sql]
dependencies: []
---

# Transaction mode set spellings missed

## Problem Statement

The SQL check that refuses setting the transaction mode in SQL matches only `autocommit`, `@@autocommit` and `transaction*`, missing MySQL/MariaDB and SQL Server spellings.

## Findings

- `Router.java:114-123`. Probe: `SET @@session.autocommit = 0`, `SET @@session.transaction_isolation = 'READ-UNCOMMITTED'`, MariaDB `SET tx_isolation = 'READ-UNCOMMITTED'` pass. Also missed: `SET SESSION CHARACTERISTICS AS TRANSACTION` (PostgreSQL), `default_transaction_isolation`, `transaction_read_only`/`tx_read_only`, SQL Server `SET IMPLICIT_TRANSACTIONS ON`, `SET TRANSACTION ISOLATION LEVEL` forms that do not parse as SetStatement.
- Result: READ UNCOMMITTED (refused by design) or a server-side transaction the JDBC layer does not know about.
- The check also reads JSQLParser types in `Router`, outside the `sql` package (architecture review).

## Proposed Solutions

### Option 1: Normalise and move into SqlAst

**Approach:** Strip `@@`, `@@session.`, `@@local.`, `session `, `local `; match a per-engine set in `Engine`; expose as a refusal from `SqlAst`.

**Pros:** Correct by engine; keeps the parser in `sql`.

**Cons:** A list.

**Effort:** Small

**Risk:** Low

### Option 2: Check the vendor after any SESSION statement

**Approach:** Read isolation and autocommit after a SET.

**Pros:** Catches spellings not listed.

**Cons:** A round trip; after the fact.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. The check moved into `SqlAst` (no JSQLParser types in `jdbc`): names normalised (`@@`, `session.`, `local.`, `global.`), autocommit, completion_type, implicit_transactions, transaction*, tx_*, default_transaction*; `SET GLOBAL`/`PERSIST` and multi-assignment SET refused outright, since the parser hides their variable. Unit test covers every spelling.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] Each spelling above is refused (unit test per engine).
- [x] No JSQLParser import in `jdbc`.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
