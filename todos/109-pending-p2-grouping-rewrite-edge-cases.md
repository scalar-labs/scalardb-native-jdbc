---
status: pending
priority: p2
issue_id: "109"
tags: [code-review, correctness]
dependencies: []
---

# Grouping rewrite: statements that fail after the rewrite, and labels and values that change

## Problem Statement

The rewrite of grouped statements (a772e20) found no wrong aggregate values. A few shapes still fail
after the rewrite or change what the application sees.

## Findings

- **Label inside an ORDER BY expression (verified), P2.**
  - `SELECT t.x, COUNT(*) FROM t GROUP BY t.x ORDER BY LOWER(x)`. Here `x` is accepted as a label
    and left as it is on top, so the result is `ORDER BY LOWER(x)` over `v`.
  - PostgreSQL, Oracle and SQL Server do not resolve output labels inside expressions, so the
    statement fails. `SqlAst.java:1722-1727`.
- **`count(t.*)` on PostgreSQL, P2.** `RowWise` ignores `AllTableColumns` inside an aggregate, and
  the aggregate on top keeps `t.*`: "missing FROM-clause entry".
- **`columnKey` ignores the schema, P3.** `SELECT s1.t.x, COUNT(*) FROM s1.t JOIN s2.t ...
  GROUP BY s2.t.x` treats `s1.t.x` as grouped and returns `s2.t.x`'s values. MySQL with
  ONLY_FULL_GROUP_BY off, or an error that turns into a result. `SqlAst.java:1757`.
- **`?` in an unaliased item's label, P3.**
  - `SUM(CASE WHEN y = ? ...)` on MySQL gets the alias with a literal `?`.
  - Connector/J's client-side prepare labels the original with the bound value; Oracle uses `:1`.

## Proposed Solutions

### Option 1
- Accept the label exception only for a bare ORDER BY column.
- Reject `AllTableColumns` / `AllColumns` inside aggregates, except as COUNT's sole argument.
- Include the schema in `columnKey`, or refuse two references of the same table name.
- Return null when the label text holds a placeholder.
- **Effort:** Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] Unit tests for each shape, and integration rows equal to the plain query on every engine.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

### 2026-10-13 - Reproduced

**By:** Claude Code
- `ReviewFindingsIntegrationTest` (uncommitted until the fixes) asserts the correct behaviour, on
  all six configurations.
- **Confirmed:**
  - `ORDER BY LOWER(x)` fails on PostgreSQL ("column x does not exist") and SQL Server ("Invalid
    column name"); Oracle passed;
  - `COUNT(m.*)` fails on PostgreSQL ("missing FROM-clause entry");
  - the label of `SUM(CASE WHEN m.x = ? ...)` on MySQL and MariaDB is the text with `?`, where
    the plain statement shows `'a'`.
- **Not tested:** the schema in `columnKey`.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".
