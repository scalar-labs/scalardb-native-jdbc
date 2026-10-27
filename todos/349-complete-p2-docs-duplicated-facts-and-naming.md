---
status: complete
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

Solution 1.

## Acceptance Criteria

- [x] Each listed fact has one home page.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: done. The lock wait lives in transactions.md's per-engine SERIALIZABLE sections (348);
  configuration.md's ScalarDB settings and Passthrough sections are now pointers; setting-up-tables keeps
  a one-line pointer; catalog_ttl_millis is defined in configuration.md, used in setting-up-tables'
  procedure and concepts' definition. "Row metadata" and "ScalarDB's metadata" renamed to transaction
  metadata / table catalog; "class path" to classpath. CONTRIBUTING.md has a "Writing the user docs"
  section: each fact's home page, the level names, the terms.
