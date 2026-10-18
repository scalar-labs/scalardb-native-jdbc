---
status: complete
priority: p1
issue_id: "252"
tags: [code-review, sqlserver, security]
dependencies: []
---

# A zero-width space after a table name hides the table from the catalog (predates 8c1)

## Problem Statement

JSqlParser keeps U+200B in an identifier; SQL Server treats it as whitespace. `SELECT x FROM
ns.t\u200B WHERE id = 1`: the parser's table is `t\u200B`, `Catalog.lookup` (Catalog.java:276)
finds nothing and treats it as plain, and SQL Server reads `ns.t` directly, dirty. With
`\u200B(NOLOCK)`, the hint pass (TableHints.java:256-268, which splits on U+200B) doesn't blank
it either and the parser reads a function. The plain-name form doesn't need hints, so it predates
8c1 (check other engines too).

## Findings

- Both forms returned an uncommitted ScalarDB value through the driver on SQL Server.
- On m8-mssql ZWSP and NBSP both act as whitespace; NBSP and BOM were refused or safe through the
  driver.

## Proposed Solutions

1. Refuse statements whose identifiers contain format / ignorable characters
   (`Character.getType == FORMAT`, `Character.isIdentifierIgnorable`), on every engine (small).
2. Plus the table-function refusal of 251.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
- 2026-10-19: fixed. analyze refuses, on every engine, an unquoted token holding a FORMAT or identifier-ignorable character (`hasIgnorableInName`); literals and quoted names are left. The parser fails on such a character at a name's start, so only inner/trailing ones needed the check.
