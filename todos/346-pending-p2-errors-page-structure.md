---
status: pending
priority: p2
issue_id: "346"
tags: [code-review, docs, errors]
dependencies: []
---

# errors.md: order, combined rows, and no retry column

## Problem Statement

Rows are in no evident order; 55000 and 08004 each carry several unrelated causes in one cell; the page's main
question, retry or not, has no column; the vendor errors users meet (23505, 1062, ORA-00001, the NOWAIT codes) are on
other pages. First-time-user review item 12.

## Proposed Solutions

1. Group rows as retry / fix the statement / fix the setup or connection, with a Retry? column; split 55000 and 08004 into one row per cause; add a short section on the database's own errors with links.

## Recommended Action

## Acceptance Criteria

- [ ] Each row has one cause and one action.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
