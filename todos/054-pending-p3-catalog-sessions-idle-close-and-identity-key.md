---
status: pending
priority: p3
issue_id: "054"
tags: [code-review, security, catalog, performance]
dependencies: []
---

# Catalog sessions stay open while idle, and the identity key includes properties that do not change identity

## Problem Statement

The driver keeps up to 64 catalogs, each holding one database session for as long as it stays in the registry. The key is a digest of every vendor property, so properties that vary per connection without changing who is asking (pgjdbc `ApplicationName` with a request id, Connector/J `connectionAttributes`, `socketTimeout`) each create a catalog and a session, up to the bound. A database with a tight `max_connections` can feel 64 extra sessions per JVM.

## Findings

- Security review of 53e168d (P2-c): 50 connections differing only in `connectionAttributes` left 52 catalogs and 51 server sessions. The fix in the follow-up commit bounds the registry at 64 and releases the session of an evicted catalog; the per-property key and the idle sessions remain.
- driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java (`catalog`, `CATALOGS`).

## Proposed Solutions

### Option 1: close a catalog's session after an idle period

**Approach:** release the connection when the catalog has made no round trip for, say, the TTL; the next miss or reload reopens it.

**Pros:** bounds idle sessions to the catalogs in use. **Cons:** a reopen after idle costs one connect. **Effort:** 1 hour. **Risk:** Low.

### Option 2: key on the properties that change identity, per engine

**Approach:** user, password, role, current schema or search path, proxy user, database, per engine, instead of every property.

**Pros:** connections that differ only in tracing properties share a catalog. **Cons:** an allowlist per engine that must stay complete, or two identities share a catalog. **Effort:** 2-3 hours. **Risk:** Medium.

## Recommended Action

**To be filled during triage.**

## Technical Details

- driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java
- driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java (`releaseConnection`)

## Resources

- Security review of 53e168d, finding P2-c; design section 1, "The catalog".

## Acceptance Criteria

- [ ] Idle catalogs hold no database session after the idle period.
- [ ] Connections that differ only in a tracing property share a catalog (if option 2).

## Work Log

### 2026-10-10 - Filed from the security review

**By:** Claude Code

**Actions:**
- The registry bound and the eviction release went into the fix commit; this records what remains.

## Notes

- Option 1 is the safer one; option 2 trades a per-engine allowlist for fewer sessions.
