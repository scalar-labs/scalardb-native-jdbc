---
status: pending
priority: p3
issue_id: "309"
tags: [code-review, testing, write]
dependencies: []
---

# No table check right after an abort or a refusal: a partial write is seen late or not at all

## Problem Statement

After a statement the driver aborts or refuses, the twin is rolled back but the tables are not
compared. A driver that writes some rows and then aborts (or refuses after running part of a write)
is detected only by the next statement whose outcome is a plain pass, or at the end. If later
statements also abort, or one overwrites the stray rows identically on both sides (a later `DELETE`
of them), the evidence is gone; when it is caught, the report blames the wrong statement.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:181-185`: rollback of the twin for refusals and aborts.
- 187-190 and 199-201: `sameTables` runs only when `outcome.equals("pass")`, never for "pass,
  aborted ..." or "refused: ...".

## Proposed Solutions

### Option 1
Run `sameTables` after every statement whose outcome is a pass, an abort or a refusal, and record
"refused/aborted, then X differs" as a failure. **Effort:** Small.

## Acceptance Criteria

- [ ] The tables are compared after every statement, whatever its outcome.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code
