---
status: complete
priority: p3
issue_id: "154"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# RETURNING items evaluated in the re-read fail or differ from native

## Problem Statement

The application's RETURNING items are evaluated by a separate SELECT over the data table. A `?` in
them is never bound and shifts the key binds; a schema-qualified column fails against the aliased
table; volatile functions (`nextval`, `clock_timestamp()`, `random()`) run a second time.

## Findings

- Probe (write reviewer): `INSERT ... VALUES (?,?) RETURNING x || ?` -> `No value specified for
  parameter 2`, the INSERT rolled back; `RETURNING pr.imp.id` -> `invalid reference to FROM-clause
  entry for table "imp"` (native pgjdbc returns 1). Volatile items: by reasoning.
- Writes.java `returned`, insertPlan's RETURNING items.

## Proposed Solutions

### Option 1
Outside a batch, return the INSERT's own RETURNING rows, its appended key columns hidden (a scrollable
statement, as the locking read does); keep the re-read for generated keys asked of a batch, where the
items are only `*` or column names.
### Option 2
Refuse items with placeholders, schema-qualified columns or function calls.

## Recommended Action


## Acceptance Criteria

- [x] The three forms return what native PostgreSQL returns, or are refused with 0AN01.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of d7be4f0 (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- The statement's own RETURNING rows are its result as the database returned them (a scrollable statement), the appended keys hidden; the re-read serves generated keys only. Test: a placeholder and a table-qualified column in RETURNING.
