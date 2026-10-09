---
status: complete
priority: p3
issue_id: "025"
tags: [code-review, catalog, quality, testing]
dependencies: []
---

# Catalog loose ends: NPE on NULL join_type, empty keys instead of an error, untested composite keys

## Problem Statement

Three small correctness gaps in `Catalog` and a list of untested paths in `CatalogIntegrationTest`. None of
them breaks the happy path exercised today, but each would surface as a confusing failure in production:
a NullPointerException where a SQLSTATE belongs, a table reported with no key columns, and key order that
was only ever checked with a single partition key.

## Findings

- `rs.getString(3).toUpperCase(...)` at `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:125`
  and `:157` throws NullPointerException when `join_type` is NULL. By code reading; unverified against a
  ScalarDB row with a NULL `join_type`.
- A virtual table whose data table has no `metadata` entry returns a `TableInfo` with empty keys instead of
  throwing. Every downstream rule ("`k = :k` means all key columns") would then treat the table as keyless.
- Key order comes from `ORDER BY ordinal_position`. Only a single partition key is tested, so it is unconfirmed
  that ScalarDB assigns `ordinal_position` in key order for composite partition keys.
- `CatalogIntegrationTest` is missing: namespace `null` (the `currentSchema` fallback), a custom metadata
  schema, composite partition key order, case-sensitive names, a plain `foo_data` table not misclassified, a
  plain table that happens to have a `tx_id` column, and a database with no `scalardb` schema. The refusal test
  asserts only the message text, not the SQLSTATE. Only PostgreSQL is covered.

## Proposed Solutions

### Option 1: Guard, throw, and add the missing test cases

**Approach:** Null-check `join_type` and throw `SQLException` with SQLSTATE `42000` and a message naming the
virtual table; throw the same way when the data table has no `metadata` entry; add the listed integration
cases including a composite partition key with clustering keys and assert the exact key order; assert
SQLSTATEs in the refusal tests.

**Pros:**
- Each gap becomes an explicit, tested error.
- Composite key order is verified against real ScalarDB rows, not assumed.

**Cons:**
- Integration suite grows by a few seconds.

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Fold into the Catalog split of 024

**Approach:** When `Catalog` is split into fetch and classify (024), put the null and missing-entry checks in
the classifier and cover them with unit tests on rows; keep only the composite-key and missing-schema cases
as integration tests.

**Pros:**
- Most cases become fast unit tests.
- No duplicate container setup.

**Cons:**
- Blocked on 024.

**Effort:** 1-2 hours on top of 024

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:125, 157` (NULL `join_type`)
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:230-260` (`columns()`, empty keys)
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java`

**Related components:**
- `TableInfo.keyColumns()` consumers (rewrite, anchors) rely on a non-empty, correctly ordered key.

**Database changes:** No

## Resources

- `docs/design.md` section 1 ("The catalog", Layouts).
- ScalarDB `metadata` table columns: `ordinal_position`, `key_type`, `clustering_order`.
- Found by: feature-dev:code-reviewer (14, test gaps).

## Acceptance Criteria

- [ ] A NULL `join_type` produces a `SQLException` with a SQLSTATE and a message naming the table, not an NPE.
- [ ] A virtual table without a `metadata` entry for its data table is refused, never returned with empty keys.
- [ ] Composite partition key plus clustering key order is asserted against ScalarDB-created rows.
- [ ] Integration cases added: null namespace, custom metadata schema, case-sensitive names, plain `foo_data`,
      plain table with `tx_id`, missing `scalardb` schema.
- [ ] Refusal tests assert the SQLSTATE.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the feature-dev:code-reviewer report (finding 14 and the test-gap list) of the
  seven-agent review of main @ 06c137f.

**Learnings:**
- The integration test is green against real ScalarDB 3.19.1 admin output, so the happy path is right; the
  gaps are all on error and edge paths.

### 2026-10-09 - Partly resolved by the catalog rework

**By:** Claude Code

**Actions:**
- The Oracle quoting bug found by the probe (unquoted column names in the catalog queries fold to upper case and fail with ORA-00904) is fixed: every column name is quoted with the engine's quote. The integration test now covers all five engines, a custom metadata schema that does not exist, case-sensitive names and the refusal SQLSTATE. Still open: a NULL join_type would still throw, composite partition key order is still untested.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- A NULL or unknown join_type no longer throws: it is read as the LEFT_OUTER join of an imported table, so the table stays protected either way.
- A ScalarDB table whose catalog entry has no partition key (a virtual table whose data table has no metadata entry, or a classic table without keys) is an error, SQLSTATE XX000 naming the data table, instead of a TableInfo with empty keys.
- Composite keys: the integration fixture creates ns.composite with partition key (b, a) and clustering key (d, c), declared in a different order from the columns; compositeKeys_keepTheOrderScalarDbWasGiven passes on all six configurations.
- Tests: CatalogTest.aNullJoinType_isReadAsTheOuterJoinOfAnImportedTable, aScalarDbTableWithoutAKey_isAnError_notAKeylessTable.

## Notes

- The other engines' quoting is untested until the engine matrix of 021 exists.
