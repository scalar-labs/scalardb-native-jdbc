---
status: complete
priority: p1
issue_id: "068"
tags: [code-review, security]
dependencies: ["055", "056", "058"]
---

# Background security review of 792b467: batches, prepared-route expiry, function denylist

## Problem Statement

The automatic security review of 792b467 (the P1 fixes) found three gaps in those fixes. The first was
reproduced on PostgreSQL as a raw write into a ScalarDB table.

## Findings

- **Batches (high, reproduced).** Each `addBatch(String)` entry was routed when added. A `SET
  search_path` or DDL earlier in the batch, or a `setSchema` between `addBatch` and `executeBatch`,
  changed what later entries' names meant: `SET search_path = m1` then `INSERT INTO s ...` under
  m1plain wrote into the ScalarDB table m1.s ("ran, written into m1.s").
- **Prepared-route expiry (medium).** The catalog expires lazily on a lookup; `Router.current` compared
  generations only, so a prepared statement on a catalog nobody looked up kept a stale route past the
  TTL, for instance after another client imported its table into ScalarDB.
- **Denylist (medium).** Oracle `DBURITYPE`/`XDBURITYPE`/`HTTPURITYPE`/`URITYPE`, `SYS_DBURIGEN` and
  `dbms_xmlsave` were missing. On PostgreSQL the parser reads `U&"query\005fto_xml"(...)` as `U & "..."`
  with the escapes undecoded, so a refused function could be called under an escaped name. (Tables
  written with `U&` fail to parse and were already refused.)

## Proposed Solutions

### Option 1: as done

**Approach:** see Recommended Action.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `addBatch` refuses a statement that changes the session or the names, outside
passthrough; `executeBatch` re-checks every entry with `Router.current` and clears the batch on a
refusal. A `Route` records `Catalog.trustedUntil()` (now plus the TTL) and `current` re-routes once it
has passed. The Oracle names were added; on PostgreSQL `U`, `&` and a quoted name written together are
refused at the token level.

## Technical Details

- `jdbc/Proxies.java`, `jdbc/Router.java`, `catalog/Catalog.java`, `engine/Engine.java`,
  `sql/SqlAst.java`.

## Acceptance Criteria

- [x] `backgroundReview_*` integration tests: batched SET and DDL refused; `setSchema` between
  `addBatch` and `executeBatch` refused with nothing written; a prepared route expires with the TTL.
- [x] Unit tests for the Oracle names and `U&` identifiers.

## Work Log

### 2026-10-10 - Background security review of 792b467

**By:** Claude Code

- Read the review's findings from its session record; reproduced the batch case; fixed all three.

## Resources

- `docs/design.md` section 1 (session state) and 4.1 (functions that run SQL text).
