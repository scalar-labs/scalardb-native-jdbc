---
status: complete
priority: p3
issue_id: "260"
tags: [code-review, sqlserver, docs]
dependencies: []
---

# Hint refusal message says 'other than NOLOCK or INDEX', which isn't the rule

## Problem Statement

Router.java:482-484 says "a SQL Server table hint other than NOLOCK or INDEX"; but `t (NOLOCK)` and
`WITH (NOLOCK, ROWLOCK)` are blanked and refused too. README and design 4.7 say "a hint the parser
cannot read" correctly.

## Proposed Solutions

1. Match the message to the docs' wording (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
- 2026-10-20: fixed. message names the hints the parser cannot read.
