---
status: complete
priority: p3
issue_id: "353"
tags: [code-review, docs, getting-started]
dependencies: []
---

# Getting started: small flow and formatting issues

## Problem Statement

Layout choice mixes layout and creation method, and "stock already exists" while B and C create it; the numbers
in steps 5 and 6 (98+30, 49+20; then +30, +20 again) are not explained; the =*> prompt is not explained; the Java
excerpt elides code without naming the full file; links split across lines; code fences without a language; the
setup output shows a real date; the diagram labels stock but not orders as a ScalarDB table; the ScalarDB docs link
points at latest. First-time-user review items 8, 15, 21.

## Proposed Solutions

1. Fix each in place.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] Each listed item fixed or deliberately kept.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-28: done, in getting-started.md: the layout choice says layout and creation method are two
  choices and that B and C create stock with plain DDL to stand for the existing table; steps 5 and 6
  explain their counts (98/49 -> 128/69 -> 158/89); the =*> prompt explained; the Java excerpt says what
  it leaves out and that the file has the full program; split link texts joined; every output fence
  tagged text; the setup's time a placeholder, explained; orders labelled a ScalarDB table in the
  diagram. Kept deliberately: the ScalarDB docs link at latest, since 3.20's docs are not published yet.
