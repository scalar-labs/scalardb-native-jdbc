---
status: complete
priority: p2
issue_id: "293"
tags: [code-review, inline, writes, security]
dependencies: []
---

# Inline metadata columns readable through a SET value, RETURNING items and generated keys by name

## Problem Statement

inlineRefusals checked the insert, set and where column lists only: `UPDATE t SET x = tx_id` copied the
row's tx_id into x; `RETURNING tx_id` was accepted; `getGeneratedKeys` asked by name (`new String[]
{"tx_id"}`) returned metadata columns. Verified on PostgreSQL and MySQL (review of 10b).

## Proposed Solutions

1. Check every column reference of the statement, and the names asked for generated keys.

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10b (ae77ce3).
- 2026-10-20: Fixed: every column reference (unquoted) is checked; keysAsked refuses a metadata name on inline. InlineIntegrationTest covers the three statements.
