---
status: pending
priority: p2
issue_id: "351"
tags: [code-review, docs, configuration]
dependencies: []
---

# configuration.md: long table cells, mixed URL rules, history in the middle

## Problem Statement

The properties table's cells are paragraphs; the URL rules (which keys, precedence, 08001) are one paragraph; the
3.19.0-alpha.1 key note interrupts; the URL list is a wide code block duplicated in getting-started; "ScalarDB
settings" does not say they go in the ScalarDB applications' own configuration. First-time-user review item 18.

## Proposed Solutions

1. Short cells with notes below; the URL rules as bullets; the old-key note in an Upgrading section; one URL table, linked from getting-started.

## Recommended Action

## Acceptance Criteria

- [ ] The table reads at a glance; no fact is in two places.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
