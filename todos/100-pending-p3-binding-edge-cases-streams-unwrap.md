---
status: pending
priority: p3
issue_id: "100"
tags: [code-review, jdbc, split-read]
dependencies: []
---

# Binding edge cases: stream refusal too broad, unwrap bypasses re-binding

## Problem Statement

Parameter re-binding is complete for every int-indexed setter, but three edges remain.

## Findings

- Streams and Readers are refused for every parameter of a split statement, even one bound once (`TOP
  (?)`, `LIMIT ?, ?`), so whether a statement accepts a stream depends invisibly on whether it split
  (Proxies.java:345-351).
- `unwrap(VendorPreparedStatement.class)` returns the vendor statement; setters there use user indexes
  as vendor indexes, binding one copy only. `CallableStatement` by-name setters and
  `registerOutParameter` are forwarded unmapped.
- `unwrap` to a vendor ParameterMetaData interface returns the vendor's 2^n x k count.
- Vendor errors on split routes quote the rewritten SQL (`T_meta`, `before_*`) and PostgreSQL positions
  point into it.

## Proposed Solutions

### Option 1: narrow and document
Refuse a stream only when its index occurs more than once, or read it once and bind bytes/strings to
every copy; document the unwrap escape hatch next to todo 060; optionally note in errors that the
statement was rewritten. **Effort:** Small.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] A stream bound to a parameter that appears once in a split statement is accepted.
- [ ] The unwrap behaviour is documented.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.

### 2026-10-13 - From todo 111

**By:** Claude Code
- A `getParameterMetaData` object taken before a transparent re-prepare still refers to the old,
  closed vendor statement.

