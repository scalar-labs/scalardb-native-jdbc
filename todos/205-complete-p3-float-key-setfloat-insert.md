---
status: complete
priority: p3
issue_id: "205"
tags: [code-review, compatibility, mysql]
dependencies: []
---

# A FLOAT key bound with setFloat is refused on a MySQL INSERT

## Problem Statement

Writes.java ~1168: Connector/J renders `setFloat(0.1f)` as the text 0.1, a DECIMAL that never equals
the stored float, so the read-back finds no row and the INSERT is refused with a message that says
to bind each key with its column's type, which is what the application did. `setDouble(0.1f)`
works. Verified.

## Proposed Solutions

### Option 1
Read back a FLOAT key column with `CAST(<value> AS FLOAT)` (MySQL 8.0.17+, MariaDB 10.4.5+), or
compare as the stored float's double.

### Option 2
Document FLOAT keys as needing setDouble.

## Acceptance Criteria

- [ ] setFloat on a FLOAT key INSERT works on MySQL (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).

### 2026-10-17 - Fixed

**By:** Claude Code
- The driver's key-matching steps bind the application's setFloat as the float's exact double; the user's own statement is unchanged. Test.
