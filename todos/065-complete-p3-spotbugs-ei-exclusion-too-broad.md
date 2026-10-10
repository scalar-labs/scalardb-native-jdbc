---
status: complete
priority: p3
issue_id: "065"
tags: [code-review, quality, security]
dependencies: []
---

# Spotbugs ei exclusion too broad

## Problem Statement

The `EI_EXPOSE_REP` exclusion covers every `Proxies$*Handler`, so a future `return vendor;`, the exact bypass this layer must prevent, would be hidden.

## Findings

- `config/spotbugs/exclude.xml:44-47`. The test-lambda `RV,OBL` exclusion is fine.

## Proposed Solutions

### Option 1: Narrow to the two fields

**Approach:** Add `<Or><Field name="connection"/><Field name="statement"/></Or>`.

**Pros:** Exact.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. Exclusion narrowed to the `connection` and `statement` fields.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] SpotBugs flags a handler returning its `vendor` field.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
