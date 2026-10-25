---
status: pending
priority: p2
issue_id: "331"
tags: [ddl, safety, setup]
dependencies: []
---

# Parsed DDL on a ScalarDB namespace, or on the setup's objects, passes through as plain

## Problem Statement

DDL on a ScalarDB table is refused (design 4.1, "DDL, TRUNCATE"). DDL that reaches the table
another way is not: the driver looks up the DDL's target as a table, finds no ScalarDB table of that
name, and passes the statement through as a plain-table statement. A ScalarDB namespace dropped or
renamed, or the setup's read view or in-flight index dropped, breaks ScalarDB or the driver's reads
of those tables.

## Findings

From a parser probe (JSqlParser 5.4, `SqlAst.analyze` on PostgreSQL and MySQL) and the code of
`Router.decide`; not yet run against a database:

- `DROP SCHEMA shop CASCADE` (PostgreSQL), `DROP DATABASE shop` (MySQL): parsed as DDL with one
  table reference, `shop`, which names no ScalarDB table.
- `DROP OWNED BY app` (PostgreSQL): parsed as DDL with the reference `app`; drops every object the
  role owns, ScalarDB's tables among them.
- `ALTER SCHEMA shop RENAME TO shop2`: parsed as OTHER, no table references.
- `DROP VIEW shop.rc_orders`, `DROP INDEX shop.orders_inflight`: the setup's objects (section 3),
  whose names are not ScalarDB tables'.

The unparsed path (todo 314) refuses each of these: it protects the namespaces and the setup's
names, and allows `CREATE`, `ALTER` and `DROP` of listed kinds only.

## Proposed Solutions

### Option 1: the same rules for parsed DDL
Refuse parsed DDL (and kind OTHER) whose words name a ScalarDB namespace or a setup object, and
DDL of a kind outside the list (`DROP OWNED`, `DROP DATABASE`, `DROP USER`, `ALTER SCHEMA`).
**Effort:** Small to Medium.

### Option 2: classify the DDL's target by its kind
Read the object kind from the AST and look a schema up as a namespace, a view or index up against
the setup's objects. **Effort:** Medium.

## Recommended Action

## Acceptance Criteria

- [ ] Each statement above is refused with `0AN01` on its engine, through the driver.
- [ ] DDL on plain tables, schemas and views still passes.

## Work Log

### 2026-10-25 - Found while hardening todo 314

**By:** Claude Code
- Parser probe as above.
