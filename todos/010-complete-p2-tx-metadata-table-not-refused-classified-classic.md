---
status: complete
priority: p2
issue_id: "010"
tags: [code-review, correctness, catalog]
dependencies: []
---

# `T_tx_metadata` is not refused and is classified as a classic ScalarDB table

## Problem Statement

The catalog refuses direct access to the two physical halves of a decoupled table that the design names (`T_data` through the virtual table's left source, and the `T_scalardb` virtual name of an imported table), but not the third one: the metadata table `T_tx_metadata`. Its own `metadata` entry carries every transaction column, so step 3 of the lookup sees a transactional table and classifies it `CLASSIC`. A user statement on `created_tx_metadata` would be rewritten as if it were a classic ScalarDB table, with `before_x` surviving as a data column. The design says the driver never touches the physical halves directly.

## Findings

Lines refer to commit 06c137f.

- `catalog/Catalog.java:148-195`: step 2 matches only `left_source_table_full_table_name`. The right source (`T_tx_metadata`) misses both virtual-table queries and falls to step 3.
- Step 3 reads the `metadata` entry of `ns.T_tx_metadata`. ScalarDB writes a `metadata` entry for each physical table (verified earlier in this project against ScalarDB 3.19.1: metadata entries exist for physical tables only, which is why the catalog reads the data table's entry for key columns). The metadata table's entry contains the key columns plus every transaction column, so `transactional = true` and the layout becomes `CLASSIC`.
- `Catalog.java:257-259`: the `before_` twin exclusion removes `before_x` only when `x` is also present; in `T_tx_metadata` there is no `x`, so `before_x` survives as a data column.
- `CatalogIntegrationTest.java:152-160` covers the refusal of `created_data` and `imported_scalardb` only.
- Verified: by code reading, consistent with the verified fact above. Not yet reproduced in the integration test; the acceptance criteria add the case.

## Proposed Solutions

### Option 1: One virtual_tables query matching all three columns, refuse on a right-source match

**Approach:** Replace steps 1 and 2 with one query: `SELECT full_table_name, left_source_table_full_table_name, right_source_table_full_table_name, join_type FROM virtual_tables WHERE full_table_name = ? OR left_source_table_full_table_name = ? OR right_source_table_full_table_name = ?`, bound to the same name three times, and branch on which column equals the name. A right-source match is refused with SQLSTATE `42000` and a message naming the virtual table to use, like the other two refusals.

**Pros:**
- One round trip instead of two or three (the simplicity and performance reviewers asked for the same change; todo 009)
- Refusal symmetric with the existing two

**Cons:**
- A name that matches more than one column across rows (should not happen; ScalarDB enforces uniqueness) needs a defined precedence; refuse on ambiguity

**Effort:** Small (1-2 hours with the integration test)

**Risk:** Low

---

### Option 2: Add a third query for the right source

**Approach:** Keep the existing structure and add a step 2b: `WHERE right_source_table_full_table_name = ?`, refusing on a hit.

**Pros:**
- Minimal diff; no change to the existing branches

**Cons:**
- A fourth round trip on a miss; the opposite direction from todo 009

**Effort:** Small

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:115-195` - the virtual_tables lookup
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:152-160` - refusal tests
- `docs/design.md` section 1 "The catalog" - list all three refusals (today it names only `_data`)

**Related components:**
- Todo 009 (single virtual_tables query)
- Todo 016 (refusal SQLSTATE contract)

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` section 1, "Layouts" and "The catalog"
- Found by: architecture-strategist (P2-5); the single-query shape confirmed by code-simplicity-reviewer (item 9) and performance-oracle (P2-2)

## Acceptance Criteria

- [ ] `Catalog.lookup` on `created_tx_metadata` and on `imported_tx_metadata` throws with SQLSTATE `42000` and a message naming the table to use
- [ ] `CatalogIntegrationTest` covers both cases, asserting the SQLSTATE and not only the message
- [ ] A miss issues one `virtual_tables` query
- [ ] design.md lists the three refused names (`_data`, `_tx_metadata`, `_scalardb`)
- [ ] Unit tests pass; integration tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by architecture-strategist (P2-5) during the `/ce:review` run on commit 06c137f
- Cross-checked against the lookup order in `Catalog.read` and the earlier verified behaviour of ScalarDB's `metadata` table

**Learnings:**
- The `before_` twin exclusion in `columns()` depends on the base column being present, which is true for data tables and virtual tables but not for the metadata half

---

### 2026-10-09 - Fix applied (catalog rework)

**By:** Claude Code

**Actions:**
- A name that is the right source of a virtual_tables row (T_tx_metadata) is refused with SQLSTATE 42000 like the data table and the virtual table; the three refusals are covered by CatalogTest and by the integration test on every engine.
- Verified: 178 unit tests; the catalog integration test parameterised over PostgreSQL 16, MySQL 8.4, MariaDB 11.4, SQL Server 2022 and Oracle Free 23 with tables created and imported by ScalarDB 3.19.1 (45 cases); full ./gradlew check.

## Notes

- Fold into the same change as todo 009's query merge if that one is accepted; the diff is the same lines.
