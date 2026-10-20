---
status: pending
priority: p3
issue_id: "294"
tags: [code-review, inline, reads]
dependencies: []
---

# Reading an inline metadata column gives a vendor error, not 0AN01

## Problem Statement

`SELECT tx_id FROM inline_table` fails with the vendor's unknown column (42703 / 42S22), since reads go
through the data columns only; nothing is exposed, but on PostgreSQL the error aborts the user's
transaction (25P02 after it). DatabaseMetaData.getColumns, forwarded to the vendor, lists the metadata
columns, unlike ResultSetMetaData (review of 10b).

## Proposed Solutions

1. Refuse a read naming a metadata column with 0AN01 before it runs (small).
2. Document getColumns, or filter it (medium).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10b (ae77ce3).
