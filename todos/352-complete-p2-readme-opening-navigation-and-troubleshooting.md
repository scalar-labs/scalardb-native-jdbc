---
status: pending
priority: p2
issue_id: "352"
tags: [code-review, docs, readme]
dependencies: []
---

# README: dense opening; docs index without a reading order; no troubleshooting for first errors

## Problem Statement

The opening is two long paragraphs with a 60-word sentence and a line past the wrap; the documentation table has no
"start here" or grouping; there is no troubleshooting table for the first errors a tester meets (08004 no catalog,
55000 not set up, 42501, no suitable driver), and questions such as how to tell the driver is in use are unanswered.
First-time-user review items 7, 16.

## Proposed Solutions

1. Lead with the scenario in two sentences; group the index as Guides / Reference / Design; add a "Troubleshooting a first run" table, linked from getting-started.

## Recommended Action

## Acceptance Criteria

- [ ] A newcomer can tell from README which page to read first.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
