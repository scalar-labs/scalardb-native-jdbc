---
status: pending
priority: p2
issue_id: "015"
tags: [code-review, security, driver]
dependencies: []
---

# The vendor URL, which may carry credentials, is echoed in exception messages

## Problem Statement

The driver's connect path puts the full JDBC URL into exception messages. Vendor URLs routinely carry credentials (`?user=&password=` on PostgreSQL and MySQL, `jdbc:oracle:thin:u/p@host`, `;password=` on SQL Server, `user:pw@host` forms), and connection pools and application frameworks log connect failures with the exception message. A failed connection would therefore write the password to application logs and monitoring systems.

## Findings

Lines refer to commit 06c137f.

- `NativeJdbcDriver.java:62-64` includes `parsed.vendorUrl()` in the "No suitable driver" message.
- `NativeJdbcUrl.java:55-56`, `:61`, `:65-71` include the whole URL in the `IllegalArgumentException` messages for a missing prefix, an empty remainder and an unknown sub-protocol.
- `NativeJdbcDriver.connect` (`:56`) wraps those messages into the `SQLException` (SQLSTATE `08001`) that the caller and its pool will log.
- `NativeJdbcDriver.getPropertyInfo` (`:83`) lets the `IllegalArgumentException` escape unwrapped with the same message (see todo 017 for the unchecked-exception part).
- Verified: by code reading; the message construction is unconditional.
- `Properties` passed to the vendor driver are never logged (checked and found acceptable by the security reviewer).

## Proposed Solutions

### Option 1: Never echo the URL; report the sub-protocol and the expected prefix

**Approach:** Build every message from the pieces that cannot carry a secret: the sub-protocol token (`postgresql`, or the unknown token for the unknown-sub-protocol case, which precedes any `:` or `/` and so cannot hold credentials), the expected prefix `jdbc:scalardb-native:`, and the list of supported sub-protocols. For "No suitable driver", name the vendor driver that must be on the classpath. Add a test asserting the message does not contain a password present in the URL.

**Pros:**
- No redaction logic to get wrong.
- Messages stay actionable (what was expected, which driver is missing).

**Cons:**
- The user cannot see the exact URL the driver saw; they have it anyway.

**Effort:** Small (under an hour)

**Risk:** Low

---

### Option 2: Redact the URL before echoing

**Approach:** Strip everything after the first `?` or `;`, and any `//user:pw@` or `thin:u/p@` segment, then include the redacted URL.

**Pros:**
- Keeps the host and database visible in the message.

**Cons:**
- Each vendor has its own credential syntax; a missed form leaks. Oracle's `u/p@` and SQL Server's `;password=` are easy to miss in a generic regex.

**Effort:** Small

**Risk:** Medium (incomplete redaction)

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:55-71` - exception messages in `parse`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:53-64,83` - `connect` wrapping and `getPropertyInfo`
- `driver/src/test/java/com/scalar/db/nativejdbc/NativeJdbcUrlTest.java`, `NativeJdbcDriverTest.java` - message assertions

**Related components:**
- Todo 017 (driver entry points: unchecked exception in `getPropertyInfo`, "No suitable driver" message)
- Todo 016 (error contract)

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` section 1, "URL scheme"
- Found by: security-sentinel (P2-3)

## Acceptance Criteria

- [ ] No exception thrown by `NativeJdbcUrl.parse`, `NativeJdbcDriver.connect` or `getPropertyInfo` contains the URL text after the sub-protocol
- [ ] A test connects with `jdbc:scalardb-native:postgresql://h/db?user=u&password=SECRET` against no vendor driver and asserts `SECRET` is absent from the exception message and from the cause chain messages
- [ ] The "No suitable driver" message names the sub-protocol and the vendor driver expected on the classpath
- [ ] Unit tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by security-sentinel (P2-3) during the `/ce:review` run on commit 06c137f
- Severity set to P2: credential exposure through logs, reachable only on a failed connect

**Learnings:**
- The sub-protocol token is the only part of a URL that is safe to echo on every vendor

---

## Notes

- Keep the same rule for any future log line that mentions a connection (todo 026, logging events).
