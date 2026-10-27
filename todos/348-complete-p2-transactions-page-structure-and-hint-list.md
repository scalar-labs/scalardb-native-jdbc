---
status: pending
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

## Acceptance Criteria

- [ ] A reader finds everything about SERIALIZABLE on one engine in one section.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
