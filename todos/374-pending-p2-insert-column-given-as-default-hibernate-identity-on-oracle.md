---
status: pending
priority: p2
issue_id: "374"
tags: [review, writes, oracle, hibernate, samples]
dependencies: []
---

# An INSERT's column given as DEFAULT is refused; Hibernate writes an IDENTITY key so on Oracle

## Problem Statement

Hibernate's Oracle dialect inserts an identity key as an explicit `DEFAULT`, `insert into
sjd.Item (name,qty,version,id) values (?,?,?,default)`, and the driver refused it with `0AN01`
("... or leaves out an AUTO_INCREMENT key, on this engine"): `leavesOutTheKey` accepts a generated
key only when the column list leaves it out. Spring Data JDBC leaves it out, which is why that
sample passes on Oracle. Found by session a8 building `samples/crud-jpa` (design 4.6's acceptance
sample beside `crud-spring-data-jdbc`: Spring Data JPA 4.1.1 on Hibernate 7.4, the same item,
repository, transfer, `@Version` and `@Lock`, the same test with ScalarDB transactions writing the
same rows, every engine and both layouts).

## The change (a8's c01142b on `crud-jpa`, with the sample 9c11f45 under it)

`Writes.withoutDefaults`, called at the top of `Writes.plan` for an INSERT: on a one-row VALUES
INSERT that lists its columns, each column whose value is the bare keyword `DEFAULT` (matched on
the value's source text, so `'DEFAULT'` and `"default"` are not) is dropped, the column list and
the row rebuilt from the source text (the list split at commas outside `""`, backticks and `[]`),
the text reparsed (`SqlAst.reparse`) and planned, the pattern of `listingColumns`. A rewrite
before planning rather than a wider `leavesOutTheKey`, since the Oracle forms after it
(`inlineInsert`, `oracleInsertBlock`, `oracleInsertEntries`, `oracleInsertAll`) copy the VALUES
row. Left as before: every column `DEFAULT`, several rows, no column list, a column list that does
not split to the row's width. `docs/sql-support.md` gains the sentence; changelog
`improvement-20261105-120000`. Tests: `WritesTest.withoutDefaults_leavesOutTheColumnsGivenAsDefault`;
418 unit tests; the PostgreSQL and Oracle integration suites; the sample on all six engine
configurations and both layouts. Not run: the MySQL, MariaDB and SQL Server integration suites
(CI on the PR).

## Review record (2026-11-05, c01142b read from `git show`)

Verdict: mergeable with one change, the order of the rewrite and the inline refusals (below).
Answers to a8's four questions:

1. Dropping a non-key column given as `DEFAULT` from a one-row VALUES is the same statement on
   every engine and in both layouts: the column takes its default, which is what the vendor's
   statement did; the driver's own columns are not among the user's. A generated key dropped is
   the left-out key every downstream form already handles. One engine difference, benign: SQL
   Server refuses `DEFAULT` for an IDENTITY column itself, so the driver now accepts a statement
   the vendor would not; the key is generated either way. The inline layout: the change is placed
   before `inlineRefusals`, so `INSERT (id, tx_state) VALUES (?, DEFAULT)` on an inline table is no
   longer refused ("a write naming ScalarDB's metadata column ... is not supported") but silently
   accepted, the column dropped and the driver's own `tx_state` written; harmless in effect, but
   the documented refusal of a client that names the metadata columns is worth keeping. The
   change asked: call `withoutDefaults` after the inline block (its `listingColumns`, which needs
   no column list, and `inlineRefusals`), before `otherKeys`; the recursion then re-enters `plan`
   with the reduced shape, where `inlineRefusals` runs again on names that are all the user's.
   Or, equivalently, have `withoutDefaults` return null when a dropped name is a metadata column.
2. The column-list split: from the first `(` after the table name's span to the last `)` before
   `rowsStart` (token-based: the first VALUES, SELECT or DEFAULT token after the name, so a quoted
   `"default"` column does not catch it). A comment or a hint holding `(` or `,` between the name
   and VALUES misreads the list, and then `listed.size() != values.size()` or the reparse returns
   null and the statement is planned as before (the refusal): fail-safe, never a wrong rewrite.
   Doubled quotes inside a quoted name toggle the quote state twice and keep the comma inside.
3. Placeholders: none is in a `DEFAULT` value, the kept values keep their order, and the
   recursive `plan` recomputes `all` from the reparsed text, so the user's bind indexes map as
   before. Right.
4. `columns` is the data table's columns with their types by name, read once from the vendor;
   passing it through unchanged is right.

The sample: mirrors `crud-spring-data-jdbc` in layout (no README there either; `docs/getting-
started.md` names both); `settings.gradle.kts` and the version catalog gain the module and Spring
Data JPA's versions. Not read line by line, a sample, not driver code; its passing on the six
configurations and both layouts is its review.

Follow-up (2026-11-05): 8ab3cad on the branch moves the `withoutDefaults` call after the inline
block (`listingColumns`, `inlineRefusals`) and before `otherKeys`, with a comment saying why, and
adds `WritesTest.anInlineTablesMetadataColumn_givenAsDefault_isRefused` (`INSERT INTO t (id,
tx_state) VALUES (?, DEFAULT)` on an inline table refused with the metadata-column message);
WritesTest 27/27, the sample green again on Oracle and PostgreSQL in both layouts per a8. The
sample's commit message reworded (it had said the sample fails on Oracle), so the hashes moved
with the trees unchanged: 9c11f45 to d6d2763, c01142b to 4edc220 (my `git diff` of each pair is
empty). The branch at 8ab3cad (d6d2763 + 4edc220 + 8ab3cad) is mergeable as a whole; landing on
the user's word, the landed head to be range-diffed against 02b0121..8ab3cad.

### 2026-11-05: pushed as PR #13 on the user's word (a8)

Local main sits about 80 commits ahead of `origin/main` (3314b0c, PR #11's merge and the #12
follow-ups), so a8 replayed the three commits onto `origin/main` as `crud-jpa-pr` and pushed that
as `crud-jpa`: d6d2763 to d9dfed1, 4edc220 to ef4be21, 8ab3cad to 8409988. My `git range-diff
02b0121..crud-jpa origin/main..crud-jpa-pr` shows all three identical, and `origin/crud-jpa` is
8409988. PR #13 (https://github.com/scalar-labs/scalardb-native-jdbc/pull/13) is open with its
nine checks in progress; on that base WritesTest 27/27 and the sample on PostgreSQL and Oracle
pass per a8. The local `crud-jpa` (8ab3cad on local main) is unchanged. The merge waits on CI and
the user's word.

## Acceptance Criteria

- [x] The rewrite placed after the inline refusals (or a dropped metadata column still refused),
      with a unit case (8ab3cad, 2026-11-05)
- [ ] CI on the PR green on MySQL, MariaDB and SQL Server too
- [ ] `samples/crud-jpa` on every engine and both layouts (done locally by a8)

## Work Log

- 2026-11-05: filed with the review of c01142b; the verdict sent to a8. Landing on the user's word.
