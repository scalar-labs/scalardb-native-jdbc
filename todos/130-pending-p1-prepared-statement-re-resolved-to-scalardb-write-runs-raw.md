---
status: pending
priority: p1
issue_id: "130"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# A prepared statement that comes to write a ScalarDB table runs unprotected

## Problem Statement

A statement prepared while its table name resolved to a plain table runs the user's text directly once
the name resolves to a ScalarDB table (after `setSchema`, a `SET` of the search path, or a table imported
after the prepare once the catalog TTL passes). The write bypasses the protocol: data changed, metadata
untouched, so ScalarDB's prepare conditions and the tombstone rules do not see it.

## Findings

- Probe: `setSchema("pl")`, `prepareStatement("UPDATE t SET x=? WHERE id=?")` on plain `pl.t`, execute;
  `setSchema("pr")` where `pr.t` is an imported ScalarDB table; execute again: `pr.t` row updated, its
  metadata unchanged.
- `Proxies.java` (non-write prepared branch, ~470-487) compares only `route.sql` with `prepared.sql`; a
  write route's sql is the source text, so they are equal and the vendor statement runs.
- The batch recheck (~494-503) has the same comparison: an `addBatch(String)` entry that becomes a
  ScalarDB write before `executeBatch` runs raw (by reasoning).

## Proposed Solutions

### Option 1
Refuse ("prepare it again") when `(route.write == null) != (prepared.write == null)`, in the prepared
branch and the batch recheck; a write route can never be run as text.

## Recommended Action

## Acceptance Criteria

- [ ] Probe scenario refused with 0AN01 "prepare it again"; batch variant too.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).
