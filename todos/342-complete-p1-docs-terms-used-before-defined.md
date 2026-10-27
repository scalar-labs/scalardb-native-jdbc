---
status: complete
priority: p1
issue_id: "342"
tags: [code-review, docs, terminology]
dependencies: []
---

# Docs use terms before defining them: in flight, read view, passthrough, layout, catalog and its TTL

## Problem Statement

"in flight" is defined only in transactions.md but used earlier in configuration.md, setting-up-tables.md and
the setup's output in getting-started; "read view", "passthrough", "layout" (inline/decoupled) and "SNAPSHOT"
are used on several pages without a definition or link; getting-started step 3 explains `--no-wait` with "catalog",
"catalog TTL" and "ScalarDB's list of tables", none defined. First-time-user review items 2 and 3.

## Proposed Solutions

1. A short "Concepts" section (README or its own page) with an anchor per term, linked from each term's first use on every page; getting-started step 3 defines the catalog in one sentence and splits the --no-wait paragraph.
2. Define each term inline on every page that uses it (duplication).

## Recommended Action

## Acceptance Criteria

- [x] Every page links the first use of each term to its definition.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: fixed. docs/concepts.md defines ScalarDB table, transaction metadata, layout, in flight, catalog,
  the setup, in-flight index, read view, passthrough and SNAPSHOT, each with an anchor; README lists it; the
  first use on getting-started, setting-up-tables, configuration, transactions, sql-support and errors links to
  the entry; getting-started step 3 defines the catalog where it explains --no-wait.
