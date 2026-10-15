---
status: pending
priority: p3
issue_id: "163"
tags: [code-review, docs, sql]
dependencies: []
---

# The copy checks are not only in tests, and a bad copy now fails instead of falling back

## Findings

- design.md:194-197, proxy-step.md:403 and the `parses()` Javadoc (SqlAst.java:1093-1097) say the checks
  run only in the tests. Gradle and Surefire add `-ea` by default, so an application's own test suite
  runs them too, and a copy JSQLParser cannot read throws AssertionError there (an Error, not a
  SQLException), while production gets a vendor error.
- In production a copy that does not parse used to fall back to the views; now an ordinary read fails
  on every execution (Router.java:847-861) and a locking read gets a vendor 42xxx quoting rewritten SQL
  instead of the clean 0A refusal (:680-696).
- No shape found triggers it: about 80 shapes over all piece combinations under `-ea` (comments around
  splices, lifted ORDER BY/LIMIT/TOP/FETCH/ROWNUM, GROUP BY/HAVING, `::`, `=-1`, `||`, NOT IN, ANY/ALL,
  hints). `needsSeparator` rules out token fusion, and placeholder counts come from the emitted tokens.

## Proposed Solutions

### Option 1
Reword the docs ("where assertions are enabled for com.scalar.db.nativejdbc") and document the
AssertionError behaviour.

### Option 2
Gate the check on a driver system property instead of `assert`; optionally retry with the view text
when a split route gets a vendor syntax error at prepare.

## Acceptance Criteria

- [ ] The docs say what actually runs where.

## Recommended Action


## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.
