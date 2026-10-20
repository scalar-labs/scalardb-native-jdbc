---
status: complete
priority: p3
issue_id: "291"
tags: [code-review, inline, test]
dependencies: []
---

# Test gap: a NULL-metadata inline row put in flight by ScalarDB

## Problem Statement

No test reads an inline row with NULL metadata (inserted by another client after setup) that a prepared
ScalarDB transaction updates or deletes; its before image is found only through `before_tx_version = 0`,
which ScalarDB writes over a NULL `tx_version` (review of 10a).

## Proposed Solutions

1. Add it to InlineIntegrationTest (small).

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
- 2026-10-20: fixed. InlineIntegrationTest: rows written after setup with NULL metadata, updated and deleted by a prepared ScalarDB transaction, read as their before images on all five engines.
