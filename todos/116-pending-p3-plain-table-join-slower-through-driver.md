---
status: pending
priority: p3
issue_id: "116"
tags: [performance, mysql, mariadb]
dependencies: ["008"]
---

# Plain table JOIN on non-key is slower through the driver than the set form

## Problem Statement

The benchmark query "plain table JOIN on non-key (1000 rows)",
`SELECT p.id, a.id FROM bn.p p JOIN c a ON a.region = p.region WHERE ...`, runs slower through the
driver than the hand-written set form, even at 100 rows in flight, where the driver should emit
that form.

## Findings

- Run of 2026-10-13 (after todo 112), ratio to the plain query, set / per-row / driver:
  - MySQL: 100 in flight 1.2 / 3.3 / 2.5; 1k 2.2 / 2.5 / 3.5.
  - MariaDB: 100 in flight 1.4 / 2.5 / 2.8; 10k 3.7 / 2.9 / 3.0.
- Earlier runs are mixed: the driver sometimes matches the set form and is often near the per-row one.
- At 100 in flight the per-row check cannot be chosen (threshold 500), so the difference is elsewhere:
  the emitted text (narrow projection, copy order), the 1,000-row result through the proxy, or
  routing cost per execution.

## Proposed Solutions

### Option 1
- Log the driver's emitted SQL for this query and EXPLAIN it next to SPLIT_DRV's.
- Time the proxy's result-set path with 1,000 rows.
- Recheck after the statement cache (todo 008), which removes per-execution routing.
- **Effort:** Small to investigate.

## Recommended Action

## Acceptance Criteria

- [ ] Cause found; the driver within 20% of the better fixed form at 100 and 1k in flight.

## Work Log

### 2026-10-13 - Recorded

**By:** Claude Code
- Seen in the todo 112 measurements; not caused by the per-row choice.
