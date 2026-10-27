---
status: pending
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

## Acceptance Criteria

- [ ] Each listed item fixed or deliberately kept.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
