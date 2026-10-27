---
status: complete
priority: p3
issue_id: "355"
tags: [code-review, docs, style]
dependencies: []
---

# snsql.md and wording across pages: small clarity issues

## Problem Statement

snsql.md lacks the "This page describes" opening; tables with empty header cells; password precedence spread over
three sentences; Installing mentions only the release zip; exit statuses packed in one sentence. Across pages:
subject-less list items ("It has no RETURNING" under a bold INSERT), "Take" for "Download", awkward phrasings
such as "Declare a transaction that only reads read-only". First-time-user review items 19, 20.

## Proposed Solutions

1. Fix in place.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] Each listed item fixed or deliberately kept.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-28: done. snsql.md: a "This page describes" opening; header cells named; the password
  sources as an ordered list, matching shell Main (SNSQL_PASSWORD, then --property password, then a
  prompt when -U is given and -w is not); Installing says "Download" and that a development build's zip
  installs the same way; exit statuses as a table. Elsewhere: "Download" in getting-started; the SQL
  Server sentence in transactions.md reworded; sql-support's INSERT bullets name their subject; README's
  index tables have header cells. Kept: list items starting "It" right after a bullet that names the
  subject (configuration.md's Database users, setting-up-tables' setup options).
