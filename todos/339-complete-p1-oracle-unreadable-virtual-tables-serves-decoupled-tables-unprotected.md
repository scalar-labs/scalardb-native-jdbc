---
status: complete
priority: p1
issue_id: "339"
tags: [code-review, catalog, oracle, security, docs]
dependencies: []
---

# Oracle: a user who cannot read virtual_tables connects, and decoupled tables are served unprotected

## Problem Statement

On Oracle a missing grant on `scalardb.virtual_tables` raises ORA-00942, the same error as a missing table.
`Catalog.load` (driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java, around 750-770) then only logs a
WARN, naming the decoupled metadata tables it found, and goes on with no virtual tables: every decoupled table reads
as plain, so the application's reads see uncommitted rows and its writes skip ScalarDB's protocol.
docs/configuration.md ("Database users") and docs/errors.md (`42501`) say the connection is refused, which is
not true for this case. Found by the docs accuracy review (2026-10-27); confirmed in the code.

## Proposed Solutions

1. Refuse at load with 42501 when `virtual_tables` is missing but `decoupledMetadataTables(metadata)` is non-empty, since the catalog then cannot be complete (small; uses the check added in c90cf3f). Pros: closes the hole on every engine; matches `absent()`'s rule that "not allowed to look" is an error. Cons: a database whose decoupled metadata tables are left behind by a dropped table would also be refused.
2. Keep the WARN and document it (smallest). Leaves the data risk.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] A user with SELECT on metadata but not on virtual_tables, on a database with a decoupled table, is refused at connect with 42501 (integration test on Oracle; PostgreSQL reports the missing grant as its own 42501, which the load already throws).
- [x] configuration.md and errors.md describe the behaviour.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: fixed. `Catalog.load` throws 42501 when virtual_tables is missing and `decoupledMetadataTables`
  finds a decoupled table's metadata table; the DEBUG line stays for a database with inline tables only.
  `CatalogIntegrationTest.aUserWhoCannotSeeVirtualTables_getsAnError_notPlainDecoupledTables` on Oracle (a user
  granted SELECT on metadata alone); design section 1, "The catalog", updated.
