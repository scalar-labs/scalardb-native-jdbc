---
status: complete
priority: p2
issue_id: "011"
tags: [code-review, correctness, sql, usability]
dependencies: []
---

# The unknown-node baseline refuses everyday ORM SQL, and the design never defines "cannot interpret"

## Problem Statement

`SqlAst.KNOWN_NODE_TYPES` is an allowlist of JSQLParser node types "measured from the statements of the tests", and `StatementShape.of(SqlAst)` empties the shape for any node type outside it, anywhere in the statement. Production behaviour therefore equals test-corpus coverage. Statements that every ORM emits are refused today: `UPDATE t SET updated_at = CURRENT_TIMESTAMP`, `n % 2 = 0`, `EXISTS (...)`, `ORDER BY x NULLS LAST`, `x IS DISTINCT FROM ?`, `SELECT TOP 5`, `INTERVAL '1 day'`, and every hinted statement. For reads the fallback is the `rc_T` route (correct, but the anchor is lost); for writes it is a hard refusal, so an application that stamps `updated_at = CURRENT_TIMESTAMP` cannot write at all. The design's only rule is "anything the parser cannot interpret: refuse", which does not say what "interpret" means.

## Findings

Lines refer to commit 06c137f.

- `sql/SqlAst.java:131-199` is the allowlist; `:408-419` produces the `type:` entries; `:439-445` `nodeTypes()` has zero callers. `StatementShape.java:113` refuses on any `type:` entry.
- Verified by the lead against the built classes and by the architecture reviewer's jshell probe. Refused today, with the node type that trips the allowlist:
  - `update t set x = ?, updated_at = CURRENT_TIMESTAMP where id = ?` (`TimeKeyExpression`)
  - `select * from t where id = ? and n % 2 = 0` (`Modulo`)
  - `select * from t where exists (select 1 from u where u.id = t.id)` (`ExistsExpression`)
  - `select * from t order by x nulls last` (`OrderByElement$NullOrdering`)
  - `select * from t where x is distinct from ?` (`IsDistinctExpression`)
  - `select top 5 * from t` (`Top`; while `LIMIT`/`OFFSET`/`FETCH` are accepted, inconsistent for SQL Server)
  - `interval '1 day'` (`IntervalExpression`), `DATE '2020-01-01'` (`DateTimeLiteralExpression`), `flag IS TRUE` (`IsBooleanExpression`)
  - every hinted statement (`OracleHint`, `MySQLIndexHint`, `SQLServerHints`)
- SQL Server `WITH (UPDLOCK, ROWLOCK)` does not parse at all in JSQLParser 5.4, although design.md 4.6 promises `@Lock` on SQL Server.
- `UPDATE ... SET updated_at = CURRENT_TIMESTAMP` is the most likely casualty: Spring Data JDBC auditing and Hibernate `@UpdateTimestamp` emit it on every update.
- The allowlist also accepts semantically relevant clauses it does not interpret (`FOR UPDATE SKIP LOCKED`, `NOWAIT`, `INSERT IGNORE`), which is todo 006.
- Three reviewers disagree on the remedy, which is why this todo presents three options rather than one.
- The working tree holds an uncommitted draft of a new design.md section 4.7 (vendor hints) that this review did not produce; it references the allowlist and should be reconciled with whichever option is chosen.

## Proposed Solutions

### Option 1: Keep the allowlist, seed it from an ORM corpus, add a corpus test

**Approach:** Keep fail-closed as the default. Build a checked-in corpus of real ORM output per dialect (Hibernate, Spring Data JDBC, MyBatis; selects, inserts, updates, deletes, auditing stamps, batch fetches, pagination, locking) and a test that asserts every corpus statement is classified. Add the node types the corpus needs (`TimeKeyExpression`, `Modulo`, `ExistsExpression`, `OrderByElement$NullOrdering`, `IsDistinctExpression`, `Top`, `IntervalExpression`, `DateTimeLiteralExpression`, `IsBooleanExpression`, `AnalyticExpression`, `ExtractExpression`, the three hint types, and whatever else the corpus surfaces). State in design.md 4.1 that "cannot interpret" means "a node type outside the known set".

**Pros:**
- Keeps the strongest safety property: any construct the driver has never seen is refused, including one that could hide a column from the `before_` substitution.
- The corpus doubles as regression coverage for the whole classifier.

**Cons:**
- The list remains a maintenance item that grows with each JSQLParser upgrade and each new ORM pattern; the first user with a construct outside the corpus is refused.
- Does not address the cost of a refusal that cannot name its reason (todo 016).

**Effort:** Medium (building the corpus is the work; 1 day)

**Risk:** Low

---

### Option 2: Scope the allowlist to the subtree that receives column substitution

**Approach:** Always refuse on a `table:` entry (a table node the enumeration missed, which the reflective cross-check catches). Apply the type allowlist only to the subtree whose columns the rewrite substitutes: the WHERE clause for `P_before` (design.md 4.2) and the SET list and select list for the classic-layout stamp (4.3). A foreign node elsewhere (the select list of a two-arm read, ORDER BY, a value expression in an INSERT) cannot hide a table, so it does not need the list. Seed the list from the corpus as in Option 1, and state the rule in 4.1.

**Pros:**
- `updated_at = CURRENT_TIMESTAMP` in a SET list of a decoupled-layout UPDATE, `NULLS LAST` in ORDER BY, and `TOP n` are accepted immediately, because none of them are in a substituted subtree.
- Keeps the safety property exactly where it matters: an unknown expression type hiding a `Column` inside WHERE would make `P_before` differ from `P` and violate invariant I4; the scoped list still refuses that.

**Cons:**
- The rule is subtler to explain and to test (which subtree is "substituted" depends on the route and the layout).
- Still a list to maintain for the WHERE clause.

**Effort:** Medium (half a day plus the corpus)

**Risk:** Low

---

### Option 3: Delete the allowlist, `nodeTypes()` and the `type:` branch

**Approach:** Remove `KNOWN_NODE_TYPES`, `nodeTypes()` (zero callers) and the `type:` branch of `unknownNodes()` (about 85 lines). Rely on what already exists: subqueries are caught by `tableRefs().size() != 1`, clause structure by `StatementShape`, hints by explicit node checks (`getOracleHint()`, `MySQLIndexHint`, `SQLServerHints`), and unknown tables by the reflective `table:` cross-check. Update the design 4.7 draft's reference to the list.

**Pros:**
- Largest simplification; no list to maintain; every parseable statement with a known shape is accepted.
- The simplicity reviewer's position: the list "adds no safety" for the routes implemented today.

**Cons:**
- Loses the one real job the list has: in the substituted WHERE subtree, an expression type that `TablesNamesFinder` does not descend into could hold a `Column` that the `before_` substitution then misses, so `P_before == P` for that term and the second UNION arm returns the wrong rows (I4). Whether such a type exists in JSQLParser 5.4 is unverified; the `table:` cross-check does not cover columns.
- Moves the fail-closed guarantee entirely onto the column walker's completeness.

**Effort:** Small (1-2 hours)

**Risk:** Medium (correctness of `P_before` depends on walker completeness with no backstop)

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:131-199` - `KNOWN_NODE_TYPES`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:389-423` - `unknownNodes` (`table:` and `type:` entries)
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:439-445` - `nodeTypes()` (unused)
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:113` - the refusal on `hasUnknownNodes()`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java` - corpus test to add
- `docs/design.md` 4.1 - define "cannot interpret"; 4.6 - SQL Server `@Lock` note; the uncommitted 4.7 draft

**Related components:**
- Todo 003 (reflective scan fails open): the `type:` check is disabled on the module path today, so whichever option is chosen must not depend on the reflective scan alone
- Todo 006 (boolean modifiers): the explicit flag checks that Option 3 relies on
- Todo 016 (refusal reason): a refused node type should be named in the error

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` 4.1 (routing table, "anything the parser cannot interpret"), 4.2 (`P_before`), 4.3 (classic stamp), 4.6 (`@Lock` on SQL Server), invariant I4
- Found by: feature-dev:code-reviewer (P2-11, 15), architecture-strategist (P2-3, with a jshell probe), code-simplicity-reviewer (P2-2)
- Verified by the lead with a probe against the built classes on 2026-10-09

## Acceptance Criteria

- [ ] A decision between the three options is recorded in design.md 4.1 as the definition of "cannot interpret"
- [ ] `update t set x = ?, updated_at = CURRENT_TIMESTAMP where id = ?` is classified as a key UPDATE
- [ ] `n % 2 = 0`, `exists (...)`, `nulls last`, `is distinct from`, `top n`, `interval`, `date '...'`, `is true` behave per the chosen option and each has a test
- [ ] A checked-in corpus of ORM output (Hibernate, Spring Data JDBC, MyBatis) is classified without refusal, with a test that fails when a corpus statement is refused
- [ ] If the list survives, `SqlAstTest.unknownNodes_` gains a test for the `table:` branch and the `merge` case no longer passes only because `Merge` is absent from the baseline
- [ ] The design 4.7 draft (if committed) matches the chosen option
- [ ] Unit tests pass; integration tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by feature-dev:code-reviewer (P2-11), architecture-strategist (P2-3, with a jshell probe of the built classifier) and code-simplicity-reviewer (P2-2) during the `/ce:review` run on commit 06c137f
- Lead re-verified the refusals listed under Findings against the built classes
- Recorded the three competing remedies as separate options rather than choosing one

**Learnings:**
- `ON DUPLICATE KEY UPDATE` and `ON CONFLICT` are refused today only because their node types are outside the list; todo 006 makes those refusals explicit so they survive any option here

---

### 2026-10-09 - Resolved by dropping the allowlist

**By:** Claude Code

**Actions:**
- Option 3 taken: KNOWN_NODE_TYPES, unknownNodes(), hasUnknownNodes() and nodeTypes() are gone. What the allowlist caught incidentally is now explicit in StatementShape: optimizer hints on all four statement kinds, table hints (MySQL index hints, SQL Server hints), TABLESAMPLE, PIVOT and UNPIVOT, in addition to the modifiers from 006. Tables are guarded by the syntax-tree cross-check (003); WHERE columns are enumerated from the tree.
- Now accepted (test of_acceptsOrdinaryExpressions_whateverTheirNodeTypes): CURRENT_TIMESTAMP, %, IS DISTINCT FROM, NULLS LAST, INTERVAL, IS TRUE, ~, window functions.
- Still open from this item: an ORM corpus test (Hibernate, Spring Data JDBC, MyBatis output per dialect) as a regression guard; and the design.md statement of the rule ("understood" = parsed as one statement, every table visited, every clause with a syntax tree, no modifier the driver does not model). The uncommitted design section 4.7 draft in the working tree still refers to SqlAst.KNOWN_NODE_TYPES, which no longer exists.

## Notes

- The one argument that separates Option 2 from Option 3 is whether a JSQLParser expression type can hold a `Column` that the column walker does not visit; settling that with a test over all `Expression` subclasses in the 5.4 jar would make the decision mechanical.
