---
status: complete
priority: p1
issue_id: "058"
tags: [code-review, security, sql]
dependencies: []
---

# Functions that change the session or run sql

## Problem Statement

Function calls inside a SELECT can change the session or run SQL the parser never sees: PostgreSQL `set_config('search_path', ...)` is classified SELECT with no tables, and dynamic-SQL functions read ScalarDB tables raw.

## Findings

- Probe (security review): `SELECT set_config('search_path', 'other', false)` is `Kind.SELECT`, no tables; `Router.java:99` sets `changesSession = false`, so the stale-schema fail-open of 055 follows.
- Probe: `SELECT query_to_xml('select * from st', ...)`, `dblink(..., 'SELECT * FROM st')`, Oracle `dbms_xmlgen.getxml('select * from st')`, SQL Server `OPENQUERY(lnk, '...')` are SELECTs with no tables; they read raw ScalarDB rows, prepared states included. The design refuses `CALL` for exactly this reason but not these.
- A user-defined function that runs `SET` or SQL internally is the general case and cannot be detected.

## Proposed Solutions

### Option 1: Per-engine deny-list plus documented limit

**Approach:** Refuse a per-engine list of functions that run SQL (`query_to_xml`, `query_to_xml_and_xmlschema`, `cursor_to_xml`, `dblink*`, `dbms_xmlgen.*`, `dbms_sql.*`, `OPENQUERY`, `OPENROWSET`, `OPENDATASOURCE`, `sp_executesql` ...) and treat `set_config` as SESSION. Document user functions that run SQL as unsupported next to the procedure-call row in design 4.1.

**Pros:** Closes the known cases; cheap.

**Cons:** A list, not a proof.

**Effort:** Medium

**Risk:** Medium

### Option 2: Re-read session state before every lookup on PostgreSQL

**Approach:** Removes the staleness half.

**Pros:** Simple.

**Cons:** One round trip per statement; does not address dynamic SQL.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. Decided 2026-10-10 (user accepted the recommendation): refuse per-engine functions that run SQL text (`Engine.runsSqlText`), anywhere in the statement; `set_config` makes the statement `Kind.SESSION`; user functions that run SQL documented as unsupported in design 4.1. Tests: SqlAstTest and both finding058 cases.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] `set_config('search_path', ...)` marks the session stale (unit + integration).
- [x] Each listed function is refused with 0A000 naming passthrough.
- [x] Design 4.1 states the user-function limit.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.
- Reproduced on PostgreSQL 16: the driver let the statement read the raw ScalarDB table; fixed, and the reproduction now ends in a refusal.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
