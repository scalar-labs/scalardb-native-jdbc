---
status: complete
priority: p1
issue_id: "036"
tags: [code-review, correctness, sql]
dependencies: []
---

# The single-table shape accepts clauses under which the engine reads or writes something other than the one table: DELETE ... USING a subquery, database links, and seven unchecked SELECT clauses

## Problem Statement

With the node-type allowlist gone (todo 011), `StatementShape` is the only thing that decides whether a statement is the simple single-table shape the rewrite rules handle. Several clauses that change what the engine touches are not checked. A `DELETE ... USING (subquery)` has no `Table` node in the USING item, so it passes as a single-table delete whose keys a rewrite would derive from the WHERE alone, deleting rows the native statement would not. An Oracle database link is dropped from the table name, so `scalar_t@remote` is classified and rewritten as the local table. And `PlainSelect` has seven fields that change a SELECT's meaning without appearing as table references.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:551-565` (`delete`): checks `d.getTables()` and joins; the comment says a USING list "adds table references, which the single-table check already refused", which is wrong for `USING (SELECT 1 ...) v`, a `VALUES` list or a table function, none of which is a `Table` node. `Delete.getUsingFromItemList()` is never read. `DELETE FROM t USING (SELECT 1 WHERE false) v WHERE id < 5` passes as a single-table DELETE with `whereColumns=[id]`; natively it deletes nothing, a key-derived rewrite deletes every row with id < 5.
- `Delete.getForPortionClause()` and `Update.getForPortionClause()` (temporal `FOR PORTION OF`) are unchecked; `Update` checks `getFromItem()` but `Delete` has no equivalent check.
- In `select()` (`StatementShape.java:435-478`), `PlainSelect` fields that change meaning and are not checked: `getOracleHierarchical()` (START WITH / CONNECT BY, Oracle is a supported engine), `getQualify()`, `getLateralViews()`, `getForXmlPath()` (SQL Server), `getMySqlSelectIntoClause()`, `getIntoTempTable()`, `getPreWhere()`.
- Oracle database links: `TableRef.name()` returns the name without the link (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:696-698`) and the replaced token range covers `scalar_t@remote` (`:606-619, :526-529`), so `select * from scalar_t@remote where id = ?` is classified as the local table and rewritten to `select * from "V_scalar_t" where id = ?` (verified on the 75106c2 build by security-sentinel, `LexProbe`); `hasTableHint` (`StatementShape.java:363-370`) does not look at the link.
- Verification: the JSQLParser 5.4 field lists are from its source (feature-dev:code-reviewer had no shell); the database-link rewrite was run on the build.

## Proposed Solutions

### Option 1: Explicit refusals for each clause, and the link in `hasTableHint`

**Approach:** In `delete`, refuse a non-empty `getUsingFromItemList()` and a `getForPortionClause()`; in `update`, refuse `getForPortionClause()`; in `select`, refuse the seven fields; add `t.getDBLinkName() != null` to `hasTableHint` so a linked reference is refused on every route. One test per clause in `StatementShapeTest`.

**Pros:**
- Small, in the pattern the modifier refusals (todo 006) already use.
- Each refusal is explicit and testable.

**Cons:**
- A blacklist: the next JSQLParser version can add a field this list does not know.

**Effort:** Small

**Risk:** Low

---

### Option 2: An explicit list of accepted fields per statement type

**Approach:** For `PlainSelect`, `Insert`, `Update` and `Delete`, enumerate the getters the shape interprets and refuse when any other non-null, non-empty field is set, checked by a small reflective test-time oracle (like `SyntaxTreeOracleTest`) that lists every getter of the four classes and asserts each is either interpreted or refused.

**Pros:**
- Fails closed on a parser upgrade; the test oracle makes the list maintained.

**Cons:**
- More code; a reflective oracle in tests only (not in main, per todo 003's rule).

**Effort:** Medium

**Risk:** Low

---

### Option 3: Option 1 now, Option 2's oracle test later

**Approach:** Land the explicit refusals, then add the getter-coverage test when the next JSQLParser upgrade is planned.

**Pros:**
- Closes the known holes immediately.

**Cons:**
- Two steps.

**Effort:** Small, then medium

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:363-370` - `hasTableHint`, the database link
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:435-478` - `select`, the seven fields
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:525-565` - `update` and `delete`, FOR PORTION and USING
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java` - one refusal case per clause

**Related components:**
- Todo 006 (modifier refusals, same pattern), todo 011 (the allowlist these clauses used to hit by accident), todo 042 (locking clauses across the whole tree for the rc_T route).

**Database changes:** None.

## Resources

- `docs/design.md` section 4.1 (what is refused), 4.3 (writes on exactly one table).
- Found by: feature-dev:code-reviewer (P2), security-sentinel (P3, database link); rated P1 by the lead because each case is a statement accepted as a single-table shape while the engine reads or writes something else.

## Acceptance Criteria

- [ ] `DELETE FROM t USING (SELECT 1 WHERE false) v WHERE id < 5` is refused by `StatementShape.of`.
- [ ] `FOR PORTION OF` on UPDATE and DELETE is refused.
- [ ] A SELECT with START WITH / CONNECT BY, QUALIFY, a lateral view, FOR XML, SELECT ... INTO (MySQL and temp-table forms) or PREWHERE is refused.
- [ ] `select * from scalar_t@remote` is refused on every route, never rewritten to the local table.
- [ ] A test exists per clause.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer read the `Delete`, `Update` and `PlainSelect` field lists in the JSQLParser 5.4 source against the shape's checks.
- security-sentinel ran the database-link statement on the 75106c2 build and showed the rewrite to the local view.

**Learnings:**
- Removing the allowlist moved the burden of "what is not interpreted" entirely onto the shape builder; every parser field that changes meaning now needs an explicit decision.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- StatementShape refuses: a DELETE with any USING item (subquery or VALUES included) and FOR PORTION OF on DELETE and UPDATE; a SELECT with CONNECT BY, QUALIFY, LATERAL VIEW, FOR XML or FOR JSON (ForClause), INTO OUTFILE, a temporary target table or PREWHERE; and a table reference carrying a database link. JSQLParser's getDBLinkName() answers the table's own name for a plain table, so the link is detected on the name token (an @ inside it), which also refuses a quoted name holding an @ that the parser mangles.
- Tests: twelve new refusal cases in StatementShapeTest.of_isEmptyForShapesTheDriverDoesNotHandle.

## Notes

- The `USING` subquery case is the data-loss one; the SELECT clauses mostly cost correctness of the read rather than data.
