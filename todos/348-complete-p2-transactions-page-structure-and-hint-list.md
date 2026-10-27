---
status: complete
priority: p2
issue_id: "348"
tags: [code-review, docs, transactions]
dependencies: []
---

# transactions.md: engine detail split across sections; SQL Server hint list incomplete

## Problem Statement

- SERIALIZABLE per engine appears under Isolation levels and again in the engine sections, with the lock-wait advice
  in several places; Conflicts and retries has no link to a retry loop; the "not checked this way" sentence on
  RIGHT/FULL JOIN, derived tables, CTEs and UNION states no consequence for the user.
- SQL Server SERIALIZABLE: "a table with a NOLOCK or INDEX hint is refused"; READUNCOMMITTED, READCOMMITTED, READPAST
  and SNAPSHOT are refused too (Router.java ~2311; SqlAst NOT_HELD).
First-time-user review item 14; accuracy review item 10.

## Proposed Solutions

1. One place per engine for SERIALIZABLE, linked from the table; the hint list completed; a link to the retry loop; say what a user does about rows the write-time check does not cover.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] A reader finds everything about SERIALIZABLE on one engine in one section.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: done. transactions.md has one SERIALIZABLE section per engine: a new PostgreSQL section
  (the jdbc-level reason, and ScalarDB's CrudException/ValidationException retry note), MySQL and MariaDB
  and SQL Server each with the jdbc-level note and their own lock-wait URL, and "No SERIALIZABLE on
  Oracle" under Oracle. The isolation table links each SERIALIZABLE row there; "ScalarDB applications
  beside the driver" is gone, its two bullets merged. Headings made unique per engine. SQL Server's
  refused hints are the full NOT_HELD set of SqlAst plus INDEX. Conflicts and retries links the retry loop
  and says what to do about unchecked reads (design 4.2's anchor list: subqueries, grouped, DISTINCT
  added; remedy from design's isolation table). configuration.md's link updated; 0 broken links.
