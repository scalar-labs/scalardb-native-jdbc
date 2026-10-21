---
status: complete
priority: p3
issue_id: "311"
tags: [code-review, testing]
dependencies: []
---

# Rows and keys compared as `List.toString` text: NULL and 'null', and commas, collide

## Problem Statement

`scalarDbAgrees`, `rows` and `InFlight.spelled` compare a row or key as `List.toString()`. SQL
NULL and the string `'null'` both spell `null`, and a value containing `", "` shifts column
boundaries, so ScalarDB reading `'null'` where the twin has NULL (or the reverse) passes the end
check, and two keys can spell alike. The corpus has no such values today, so nothing is hidden now;
it matters as the corpus grows (T4).

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:410, 423, 437`: `row.toString()` lists compared.
- 284-286: `rows` keys and rows as `toString`.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/InFlight.java:175-181, 135-139`: held keys spelled the same way.

## Proposed Solutions

### Option 1
Compare `List<String>` (or `List<List<String>>`) values instead of their text; keys as
`List<String>`. **Effort:** Small.

## Acceptance Criteria

- [ ] A row with `'null'` against one with NULL fails `scalarDbAgrees`.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code

### 2026-10-21 - Fixed

Fixed with the other T3 review todos (305-311); five engines pass, both phases.
