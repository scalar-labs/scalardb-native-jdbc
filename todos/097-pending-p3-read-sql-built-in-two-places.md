---
status: pending
priority: p3
issue_id: "097"
tags: [code-review, architecture, correctness]
dependencies: []
---

# The split fragments and the rc_T view text are built in two places with different rules

## Problem Statement

`SplitRead` (inFlight, data, meta, keyJoin, the LEFT_JOIN arm of committed) rebuilds the arms of
`Setup.viewDdl` character for character. A fix to the before-image condition or the state set must be
made twice; if one is missed, the split and rc_T return different images for the same row. The rules
already differ: NOT_EXISTS and DISTINCT_SET treat a row as committed unless `tx_state IN (1, 2)`, while
LEFT_JOIN and rc_T require `COALESCE(tx_state, 3) = 3`, so on SQL Server one statement applies both.
Harmless only if ScalarDB never writes another state to a record (not verified).

## Findings

- `SplitRead.java:115-198` against `Setup.java:373-416`; `ReadIntegrationTest.assertSettled` rebuilds the
  metadata name a third time.
- The three committed forms are covered only by the five-engine integration run; no unit test checks
  the text.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Duplication confirmed by reading. The predicate difference is harmless today: ScalarDB 3.19.1 defines
states 1-5, but no record composer writes ABORTED (4) or UNKNOWN (5) to a record; ABORTED appears only
as a coordinator state (`RecoveryHandler`). Lowered to P3: a maintenance risk, not a wrong result.

## Proposed Solutions

### Option 1: one builder
Move the arm builders, qualified names and `keyJoin` to one place (`TableInfo` or a small
`catalog/ReadSql`) used by both; pick one committed predicate, or assert the state invariant against
ScalarDB's composers. Add string tests per committed form. **Effort:** Small. **Risk:** Low (the view
text must stay byte-identical, or `viewMatches` must allow it).

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] One source for the arms; the view DDL unchanged or migrated.
- [ ] The committed-row rule is the same in every form, or the invariant is documented with a ScalarDB
      reference.
- [ ] Unit tests of SplitRead's text per form.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
