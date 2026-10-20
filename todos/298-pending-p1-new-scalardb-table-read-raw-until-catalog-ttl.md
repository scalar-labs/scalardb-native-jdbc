---
status: pending
priority: p1
issue_id: "298"
tags: [catalog, correctness, operations]
dependencies: []
---

# A table imported into ScalarDB after the catalog loaded is read raw until the TTL

## Problem Statement

The driver trusts a loaded catalog for its TTL (60 s by default). A table imported into ScalarDB, or
created through it, after a driver loaded its catalog is unknown to that driver until the TTL runs out:
it classifies the name as a plain table and passes statements through, so a read returns the rows a
ScalarDB transaction holds prepared (uncommitted) and a write bypasses the protocol. Found by the 9c
sample test: on PostgreSQL an inline table imported seconds after the decoupled run read a prepared
value (40 for the committed 4); the decoupled layouts behave the same. The design accepts the TTL for a
new setup ("other drivers see a new setup after their catalog TTL"), but not for reading a ScalarDB
table raw. The setup only helps after the TTL too: before it, the table is plain, not "not set up"
(55000).

## Proposed Solutions

1. Document the operating rule: import or create tables, run the setup, then wait out the catalog TTL
   (or restart the applications) before they use them (small).
2. A name the catalog does not know as a ScalarDB table is checked against ScalarDB's metadata before
   it is passed through, cached negatively only briefly (medium; the cost is a lookup per new name).
3. `scalardbnative.refreshCatalog` client info, from todo 026, for an operator's tool (small).

## Acceptance Criteria

- [ ] A table imported after the catalog loaded is never read raw, or the rule is documented

## Work Log

- 2026-10-20: found by SpringDataSampleIntegrationTest / JdbcSampleIntegrationTest (9c); the sample
  tests set scalardbnative.catalogTtlMs=0.
- 2026-10-20: raised to P1 by the review of 9c: the catalog outlives the connection pool, so a new
  pool in a running application still used the catalog loaded before the import; without the TTL
  setting the inline sample read the prepared value (40 for 4).
- 2026-10-20: decided with the user for the release: the operating rule (import, setup, restart the
  applications or wait the TTL, then ScalarDB writes) documented in the README and the tool's help;
  the samples' tests set the TTL to 0 in place of the restart. Option 2 or 3 after the release.
