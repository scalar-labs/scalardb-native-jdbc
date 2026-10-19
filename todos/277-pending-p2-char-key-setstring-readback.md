---
status: pending
priority: p2
issue_id: "277"
tags: [code-review, oracle, correctness]
dependencies: []
---

# A CHAR key bound with setString can never be inserted on Oracle

## Problem Statement

The INSERT read-back by value (Writes.java:510-520) compares `d.k = ?`; a VARCHAR2 bind is compared
with a CHAR column without blank padding, so `'a'` never matches the stored `'a    '` (a literal
does). `INSERT INTO rv.ch (k, x) VALUES (?, 0)` with `setString(1, "a")` on `k CHAR(5) PK` fails
with 0AN01 "the key of row 1 of the INSERT reads back as 0 rows"; the vendor driver inserts it.

Related (P3): a VARCHAR2 key bound with setInt: `d.k = 7` converts every key with TO_NUMBER and
fails with ORA-01722 on a key like 'abc' (the vendor inserts '7').

## Proposed Solutions

1. On Oracle read back with `d.k = CAST(? AS <column type>)`, the type from `columns()` (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
