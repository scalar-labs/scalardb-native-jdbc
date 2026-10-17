---
status: pending
priority: p1
issue_id: "187"
tags: [code-review, correctness, jdbc, mysql]
dependencies: []
---

# On MySQL the generated-key path trusts getGeneratedKeys for keys it did not generate

## Problem Statement

When an INSERT on MySQL does not give every key as a literal or placeholder (Writes.java ~279,
~1067), the keys are taken from the vendor `getGeneratedKeys`, which Connector/J derives from
LAST_INSERT_ID plus the row index. The `keys.rows.size() != count` check passes in all of these
(verified through the driver, scratchpad/rv2 Gk/Rv2):

- Expression keys `VALUES (70 + 0, 1), (71 + 0, 2)`: reported [71, 72]; 72 already existed: row 70
  got no metadata, the committed row 72's metadata was taken over (version 2).
- `(5 + 0), (NULL)`: reported [102, 103]; with 103 absent, a misleading 40001.
- A non-key AUTO_INCREMENT column (`k INT DEFAULT 99 PRIMARY KEY, seq INT AUTO_INCREMENT UNIQUE`):
  `INSERT INTO u (x) VALUES (9)` reports seq=7 as the key; row 99 unstamped, row 7 taken over.

## Proposed Solutions

### Option 1
Use the generated path only when the single key column is the table's AUTO_INCREMENT column
(information_schema.columns EXTRA, read with the catalog or once per plan) and every row gives it
NULL, DEFAULT or omits it; refuse every other non-literal key.

### Option 2
Refuse any INSERT whose keys are not all literals/placeholders on MySQL, and support AUTO_INCREMENT
only through an omitted column. Simpler, still needs the AUTO_INCREMENT check.

## Acceptance Criteria

- [ ] Expression keys and non-key AUTO_INCREMENT columns are refused (0AN01) or handled exactly
- [ ] No committed row is restamped by an INSERT that did not write it
- [ ] Integration tests for the three cases

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
