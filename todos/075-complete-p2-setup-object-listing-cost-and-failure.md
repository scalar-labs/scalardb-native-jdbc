---
status: complete
priority: p2
issue_id: "075"
tags: [code-review, performance, catalog]
dependencies: []
---

# Setup object listing cost and failure

## Problem Statement

Each catalog load lists every view and index of whole application schemas, and a failure of that listing, even a transient one, replaces a good snapshot with "nothing is set up" for a full TTL.

## Findings

- Imported tables live in application schemas (`public`, `dbo`), so the listing returns every index of the application at each load, including the synchronous one at connect. MariaDB and older MySQL `information_schema.STATISTICS` open every table of the schema (seconds at 10k tables).
- `Catalog.load` catches every SQLException from the listing, connection failures included, so `withConnection`'s reconnect never sees it; every decoupled table is refused with 55000 for the TTL.
- `Setup.table` re-lists the whole schema once per table.
- An IN list beyond 2100 parameters (SQL Server) or 1000 expressions (Oracle) fails, safely but silently.

## Proposed Solutions

### Option 1: Filter and keep the last good answer

**Approach:** Filter by the metadata tables and `rc_` names; rethrow connection failures; on any other failure reuse the previous snapshot's set-up objects and log why; list once per Setup run.

**Pros:** Cheap loads; no false refusals from a blip.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. The listing asks for the setup's names only; a lost connection is rethrown; any other failure keeps the previous snapshot's answer; Setup lists once per run.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] The listing names only the driver's objects.
- [x] A listing failure keeps the previous answer.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Confirmed in the code: Catalog.load catches every SQLException from the listing and records no setup objects; the listing is per schema. The cost on MariaDB/older MySQL information_schema.STATISTICS is from the reviewer, not measured.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
