---
status: complete
priority: p3
issue_id: "194"
tags: [code-review, quality, mysql, mariadb]
dependencies: []
---

# LOCK IN SHARE MODE rewrite: trailing comments and WAIT n refused, leniency

## Problem Statement

`SqlAst.lockInShareModeAsForShare` (SqlAst.java:301-316) anchors at the end of the text, so a trailing
`/* */` or `--` comment after the clause (sqlcommenter style) or MariaDB's `WAIT n` leaves it
unparsed and the statement refused; multi-statement text reports a parse error rather than "N
statements". It also accepts `LOCK IN SHARE MODE NOWAIT` on MySQL, which MySQL rejects (1064).
Verified with rv3_lisp.sh. Literals are never matched; offsets kept.

## Proposed Solutions

### Option 1
Allow trailing comments in the lookahead; support or refuse WAIT n explicitly. Leniency: accept.

## Acceptance Criteria

- [x] A trailing comment after LOCK IN SHARE MODE parses (unit test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.

### 2026-10-17 - Fixed

**By:** Claude Code
- The LOCK IN SHARE MODE rewrite allows trailing comments; WAIT n stays refused; leniency kept. Unit test.
