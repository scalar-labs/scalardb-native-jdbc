---
status: pending
priority: p2
issue_id: "350"
tags: [code-review, docs, setup, sqlserver]
dependencies: []
---

# setting-up-tables.md: check's object report, teardown's extra work, SQL Server session options undocumented

## Problem Statement

- `check` also reports views, triggers, procedures and cascading foreign keys that reach a ScalarDB table, and
  exits 1 for them (tools Main ~250; Check.objects).
- `teardown` also unlocks the statistics and removes the tx_state histogram the setup made on Oracle and MySQL
  (design ~642).
- SQL Server's filtered index needs QUOTED_IDENTIFIER, ANSI_NULLS, ANSI_PADDING and ANSI_WARNINGS on in every session
  that writes the metadata table, ScalarDB's included (design ~666); not in configuration.md.
- Structure: the page opens with layouts before saying what the setup is; Java API sentence without a link; --grant's
  syntax for several users unstated; teardown has no example.
Accuracy review items 11, 12; first-time-user review item 17.

## Proposed Solutions

1. Add the three missing facts; reorder the page: what the setup does, running it, commands, adding a table.

## Recommended Action

## Acceptance Criteria

- [ ] Each subcommand's documented effects match the tools' code.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
