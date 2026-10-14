---
status: pending
priority: p1
issue_id: "120"
tags: [code-review, correctness, split-read]
dependencies: []
---

# A table in a nested join on the nullable side is split as INNER

## Problem Statement

`select c.id, o.id, d.id from c left join o join d on d.oid = o.id on o.cid = c.id` puts `d` on the
nullable side of the LEFT JOIN, but `splittableRefs()` places it INNER and writes two copies. Each copy
NULL-extends every `c` row that has no match in it, so a preserved row with no match comes back once per
copy, and a row with a match in one copy also comes back NULL-extended from the other. Wrong rows on
every engine. Valid syntax on PostgreSQL, MySQL, SQL Server and Oracle. The same happens when `o` is a
plain table.

## Findings

- JSqlParser reads it as `Join(o, left, on=[])` followed by `Join(d, inner, on=[d.oid = o.id, o.cid = c.id])`.
- `SqlAst.java:1437-1447` skips the LEFT join of `o` and collects `d` as an inner item.
- Emitted (probe): `... from c left join rc_o o join c_d d on d.oid = o.id on o.cid = c.id UNION ALL
  ... join f_d d ...`.
- PostgreSQL and MySQL runs against the logical tables: ten extra NULL-extended rows (`1||`, `2||`, ...).
- Predates 3c (same at d719647). 3c's `leftJoin()` refuses ON lists of size other than 1, so `o` itself
  is not split, but `d` is.

## Proposed Solutions

### Option 1: refuse nested ON lists
- In `splittableRefs()` (and `split()`), return no refs when any join has more than one ON expression,
  or an outer join without ON/USING is followed by more joins.
- **Effort:** Small. **Risk:** Low; the statement falls back to `rc_`.

### Option 2: place tables under a nested ON as NULLABLE
- More copies and a nested-scope NOT EXISTS; not worth it for a rare shape.

## Recommended Action

## Acceptance Criteria

- [ ] SplitTest: the statement above, with `d` (and with `o` plain), is not split.
- [ ] An integration case compares it with the plain read.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of d719647..9a00150; confirmed with the split probe and on PostgreSQL and MySQL.
