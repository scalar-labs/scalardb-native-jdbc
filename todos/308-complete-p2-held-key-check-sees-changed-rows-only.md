---
status: complete
priority: p2
issue_id: "308"
tags: [code-review, testing, write, in-flight]
dependencies: []
---

# "Writes a key in flight" is computed from rows whose values changed: a no-op update of a held row need not abort

## Problem Statement

The keys a statement writes are the twin's keys whose row text differs before and after. A statement
that matches a held row but leaves its values as they were (`SET name = name`, a `SET` to the value
already there, an upsert's update to the same value) changes nothing on the twin, so it is not
required to abort, and a driver that writes such a row in flight (overwriting the prepared record or
its before image, or skipping the in-flight check when the new values equal the old) is not
detected by this check. ScalarDB treats any write of the row as a write. The current corpus has, as
far as traced, no such statement on an original (holdable) key, so the gap is a blind spot rather
than a masked failure today.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:169-179`: `reached` from `changed(before, after)`.
- 295-311: `changed` compares the normalised row text.
- Also: `reached` is computed only when the twin succeeds (169); fine, since a failing statement
  writes nothing.

## Proposed Solutions

### Option 1: the matched keys
For `UPDATE`/`DELETE`, run the statement's target and `WHERE` as `SELECT <keys>` on the twin
before it; those keys are written whatever the values. **Effort:** Medium (needs a light rewrite
per form; joins in `UPDATE`).

### Option 2: add statements that expose it
Add `UPDATE {c} SET name = name` and `UPDATE {k} SET v = v WHERE b = 1` to the corpus and mark them
"must abort when a matched key is held", using the twin's `SELECT` of the same predicate.
**Effort:** Small.

## Acceptance Criteria

- [ ] A write matching a key in flight with unchanged values must abort in the in-flight phase.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code

## Resources

- docs/plans/testing.md, milestone T3.

### 2026-10-21 - Fixed

Fixed with the other T3 review todos (305-311); five engines pass, both phases.
