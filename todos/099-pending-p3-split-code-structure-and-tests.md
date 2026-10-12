---
status: pending
priority: p3
issue_id: "099"
tags: [code-review, quality, simplicity]
dependencies: []
---

# Split code structure: SqlAst size, duplicated planning, one-use types, test gaps

## Problem Statement

The split read added about 570 lines to a 2045-line `SqlAst.java`, with some duplication and machinery
for cases the caller already refuses.

## Findings

- `SqlAst` lines 1092-1658 could move to a package-private `sql/SplitSql.java`.
- The plan-then-check sequence (`planRewrite`, `unrepresentable || lockTargetIsRenamed ||
  !collectReplacements`) is written twice (788-795, 1394-1405).
- Guards for shapes `Router.read` already refuses (INTO, locking at 1197-1198, 1399); redundant
  `copies.isEmpty()` / PlainSelect checks (1378).
- `Route` carries `parameterSources` and `parameters`; storing the nullable `SplitText` would do.
- `SplitRead.Check` enum and `check()` have one consumer; `MAX_TABLES` could be private.
- `Lift`/`Piece` fields used once, `sameAlias` one use, `appendToken` used only by Emitter, EOF test
  duplicated (1057, 1383).
- Benchmark `direct(Mode)` switch could be `Mode.valueOf(...)`.
- `Engines.ORACLE.url()` performs a SYSDBA `ALTER SYSTEM`: a side effect hidden in a getter.
- Tests: `*` with an aliased table and `a.*` untested; the star test asserts only `startsWith`.

## Proposed Solutions

### Option 1: one structural commit
Move the split code, add `replacements(...)`, simplify `Route`, inline the one-use types, rename the
Oracle hook, add the star-alias tests. **Effort:** Medium. **Risk:** Low.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] SqlAst split; behaviour unchanged (unit and integration tests pass).
- [ ] Star tests cover `t.*` and an aliased table.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
