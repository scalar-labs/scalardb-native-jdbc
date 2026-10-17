---
status: complete
priority: p1
issue_id: "201"
tags: [code-review, correctness, data, mysql]
dependencies: []
---

# The MySQL key read-back can match an older committed row and take it over

## Problem Statement

Writes.java ~342-355, ~1157-1176: exactly one match proves only that the given values match one
row. When MySQL stores something other than the value given, that row can be an older committed
one, which is then restamped (tx_version 1 -> 2) while the inserted row gets no metadata; count 1,
no error. Verified through the driver (scratchpad rv5):

- a BEFORE INSERT trigger changing the key (`SET NEW.k = NEW.k + 100`) with committed k=5: INSERT
  (5, 1) stores 105; the read-back k = 5 finds the committed 5, restamped;
- non-strict sql_mode with a VARCHAR(5) key and committed '1e5': INSERT 100000 stores '10000'; the
  read-back k = 100000 matches only '1e5', restamped.

## Proposed Solutions

### Option 1
Run each row's match before the INSERT too, inside the write's savepoint, and require 0 before and
exactly 1 after.

### Option 2
Also refuse data tables with triggers (information_schema.triggers, asked once with the
AUTO_INCREMENT check) and sessions without STRICT_TRANS_TABLES/STRICT_ALL_TABLES.

## Acceptance Criteria

- [ ] Neither scenario restamps a committed row (tests)
- [ ] A normal INSERT still costs no more than one extra read per row

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).

### 2026-10-17 - Fixed

**By:** Claude Code
- One read per INSERT, before it, asks whether any row matches the given key values; a match plus a successful INSERT is refused (0AN01). Trigger case tested on MySQL.
