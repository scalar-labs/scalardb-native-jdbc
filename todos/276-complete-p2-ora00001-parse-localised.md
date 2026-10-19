---
status: complete
priority: p2
issue_id: "276"
tags: [code-review, oracle, correctness]
dependencies: []
---

# ORA-00001 parsing throws outside English, or without a message

## Problem Statement

`keyViolated` (Writes.java:1625-1643) looks for "constraint ("; ojdbc localises messages by the JVM
locale (Japanese `一意制約(P8.SYS_C…)`, German `Unique Constraint (`, French `violation de contrainte
unique (`). Not found: open = close = dot = -1, the guard `dot < open` is false, and
`m.substring(11, -1)` throws StringIndexOutOfBoundsException; a null message gives an NPE.

## Findings

`Locale.setDefault(Locale.JAPAN)`, `INSERT INTO rv.imp (id, x) VALUES (1, 'z')` with key 1 present:
StringIndexOutOfBoundsException instead of SQLIntegrityConstraintViolationException 1.

## Proposed Solutions

1. Take `OWNER.NAME` from the first parenthesised `X.Y` after `ORA-00001` whatever the language,
   and return true when none is found (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
- 2026-10-19: fixed. The constraint is found by its (OWNER.NAME) pattern, whatever the language; none found is "cannot be told", true. Tested in MoreWrites reviewOf8d1 with NLS_LANGUAGE GERMAN (the JVM default locale did not change the messages in the test JVM); fails with the old parsing (StringIndexOutOfBounds).
