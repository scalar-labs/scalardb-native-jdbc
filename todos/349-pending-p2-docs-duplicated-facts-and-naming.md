---
status: pending
priority: p2
issue_id: "349"
tags: [code-review, docs, consistency]
dependencies: []
---

# The same facts on several pages, and the same thing under several names

## Problem Statement

Repeated, and able to drift: the ScalarDB connection level rule (getting-started, setting-up-tables, configuration,
transactions), the short lock wait (transactions x3, configuration), passthrough (configuration, sql-support, snsql,
with different scopes), catalog_ttl_millis (three pages), metadata-column hiding, 40001. Names: "metadata" for both
the transaction metadata and the table catalog; "the setup" for the run and the subcommand; class path/classpath;
READ COMMITTED/REPEATABLE_READ/REPEATABLE READ spellings. First-time-user review items 9, 10.

## Proposed Solutions

1. Keep each fact on one page and link from the others; a short style rule for level names and terms.

## Recommended Action

## Acceptance Criteria

- [ ] Each listed fact has one home page.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
