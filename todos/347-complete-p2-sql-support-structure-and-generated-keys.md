---
status: pending
priority: p2
issue_id: "347"
tags: [code-review, docs, sql-support]
dependencies: []
---

# sql-support.md: engine sections lack headings and anchors; generated keys by index unstated

## Problem Statement

- Engine sections use bold pseudo-headings, so links like "key rules on MySQL and Oracle (below)" land on MySQL
  only; the "cannot parse" section has a nested list with a stray paragraph; Upserts, a recipe with code, sits
  between reference sections; "Statement cost" is a performance topic.
- "Batches and generated keys (getGeneratedKeys)" run: by column name only; by column index, and a metadata column,
  are refused (design ~2322; Writes.java ~8567).
First-time-user review item 13; accuracy review item 14.

## Proposed Solutions

1. H3 headings per engine topic with links from the tables; generated keys "by column name"; the parse rules as a small table; Statement cost moved to a performance note or kept with a clear heading.

## Recommended Action

## Acceptance Criteria

- [ ] Every in-page link lands on the section it names.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
