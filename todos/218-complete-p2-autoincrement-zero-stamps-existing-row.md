---
status: complete
priority: p2
issue_id: "218"
tags: [code-review, correctness, mysql, regression]
dependencies: []
---

# An explicit 0 for an AUTO_INCREMENT key stamps an existing committed row with key 0

## Problem Statement

Writes.java:172-212 (storesOtherKeys replaced todo 201's prior read) and the read-back in
InsertPlan.apply (~1280-1296). A table with an AUTO_INCREMENT key and a committed row k=0 (e.g.
restored by mysqldump with NO_AUTO_VALUE_ON_ZERO); strict session, no trigger.
`INSERT INTO ai (k, x) VALUES (0, 1)` (or a placeholder bound to 0): MySQL generates k=1, the
read-back `WHERE d.k = 0` finds the old row and stamps it. Verified on MySQL 8.4 (scratchpad
rv8b1/me probe Q): count=1, ai = [0,0][1,1], meta row 0 restamped v1 -> v2 with the driver's tx id,
new row 1 has no metadata. The prior read refused this case.

## Proposed Solutions

### Option 1
On an AUTO_INCREMENT key column (autoIncrement(run) exists), execute with RETURN_GENERATED_KEYS and
refuse if MySQL generated a value.
### Option 2
Refuse a literal 0/NULL key at plan time and a bound 0/NULL at run time.

## Acceptance Criteria

- [x] 0 or NULL given for an AUTO_INCREMENT key never stamps another row
- [x] Integration test on MySQL
- [x] design.md §4.4 states the case

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).

### 2026-10-18 - Fixed

**By:** Claude Code
- Fixed: the read-back of an AUTO_INCREMENT key column adds `d.k <> 0` (columns() now reads `extra`), so an explicit 0 reads back no row and is refused; NO_AUTO_VALUE_ON_ZERO users giving 0 are refused too (conservative). Connector/J's generated keys report explicit values too, so Option 1 could not tell them apart. Test: MoreWritesIntegrationTest reviewOf8a (row 0 restored, literal and bound 0 refused, nothing stamped).
