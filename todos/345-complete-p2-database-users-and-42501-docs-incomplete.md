---
status: complete
priority: p2
issue_id: "345"
tags: [code-review, docs, configuration, security]
dependencies: []
---

# Database users: missing privilege caveats, and 42501 described inconsistently

## Problem Statement

- The read view runs with its owner's rights on PostgreSQL before 15, SQL Server and Oracle (design ~703-712):
  column privileges are not enforced through it, so `--grant` only to users that may read every column.
- The application user also needs its usual privileges on the table and `<table>_tx_metadata`.
- `42501` is also raised when the metadata schema exists but ScalarDB has not created `metadata` yet (Catalog.java
  ~878-890), before allow_no_catalog applies.
- errors.md says SELECT on scalardb.metadata and scalardb.virtual_tables; configuration.md says virtual_tables only for
  decoupled tables, in the schema metadata_schema names.
Accuracy review items 5, 7; first-time-user review item 11.

## Proposed Solutions

1. Add the two privilege notes to configuration.md and setting-up-tables.md's --grant; align errors.md's 42501 row with configuration.md, including the not-created-yet case.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] configuration.md, setting-up-tables.md and errors.md state the same privileges.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: done. configuration.md's Database users: virtual_tables when the database has decoupled
  tables (matching Catalog's 339 refusal), the usual ScalarDB privileges on the table and its
  _tx_metadata, and the owner's-rights caveat of design 4.x "Privileges". setting-up-tables.md's
  --grant carries the caveat with a link. errors.md's 42501 row names both causes of Catalog.absent
  (not readable, not created yet) and the same grants, with a link.
