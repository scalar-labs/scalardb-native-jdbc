---
status: complete
priority: p3
issue_id: "017"
tags: [code-review, driver, quality]
dependencies: []
---

# Driver entry points: unchecked exceptions, forwarded properties, case-sensitive vendor URL, no deregistration

## Problem Statement

`NativeJdbcDriver` and `NativeJdbcUrl` are the only code a connection pool or an application touches directly, and several of their edges break the `java.sql.Driver` contract or the design's own rules: a malformed URL escapes `getPropertyInfo` as an unchecked exception, a null `Properties` is passed to the vendor driver, the driver's own properties are neither listed nor stripped before forwarding, a mixed-case sub-protocol produces a vendor URL no vendor driver accepts, a missing vendor driver is reported without naming it, and the driver can never be deregistered. None of these affects a correctly configured application, which is why they are P3, but each one produces a confusing failure for the person who hits it.

## Findings

All line numbers refer to commit 06c137f; paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

- `NativeJdbcDriver.getPropertyInfo` (`NativeJdbcDriver.java:78-85`) calls `NativeJdbcUrl.parse` unguarded at `:83`, so `jdbc:scalardb-native:sqlite:x` throws an unchecked `IllegalArgumentException` from a method contracted to throw `SQLException`. `connect` (`:53-57`) wraps the same case into SQLSTATE `08001` correctly. By code reading.
- `getPropertyInfo` passes a null `info` straight to the vendor driver; several vendor drivers dereference it. Use `new Properties()`.
- `getPropertyInfo` forwards only the vendor's `DriverPropertyInfo`, so the driver's own properties are never listed by the JDBC-standard discovery that pools and tools use. `docs/design.md` names `inflightWaitMs`, `passthrough` and `metadataSchema`; the optional `getMetaData` filtering, the SERIALIZABLE re-read row cap and the catalog TTL are knobs with no property name yet.
- `acceptsURL(null)` throws `NullPointerException` from `requireNonNull` instead of returning false (the JDBC contract says a driver should return false or throw `SQLException`).
- `connect` (`:58-60`) forwards the whole `Properties`, including the driver's `scalardbnative.*` namespace, which the design says to strip before handing to the vendor.
- A missing vendor driver surfaces as `SQLException("No suitable driver", "08001")` for a URL this driver accepted; nothing says which vendor jar is missing. The SQLSTATE is right, the message is not actionable. Note that the message must not echo the URL either (see todo 015). `DriverManager.getDriver` also performs a caller-classloader check that can fail in fat-jar and container classloaders.
- `NativeJdbcUrl.parse` (`NativeJdbcUrl.java:73`) keeps the sub-protocol's original case in `vendorUrl`. **Verified** against the built classes: `jdbc:scalardb-native:MariaDB://h/db` gives `subProtocol=mariadb` but `vendorUrl=jdbc:MariaDB://h/db`; vendor drivers match their prefix case-sensitively, so `getDriver` fails. `NativeJdbcUrlTest.parse_lowerCasesTheSubProtocol` asserts only `subProtocol()`, so it passes anyway.
- `NativeJdbcDriver` registers in a static initializer (`:32-38`) and never deregisters, a classloader leak on container redeploy. pgjdbc's `deregister()` pattern is the standard fix.
- `MAJOR_VERSION` / `MINOR_VERSION` (`:26-28`) are hardcoded rather than derived from `gradle.properties` or the jar manifest.
- Tests missing: `connect` with a vendor driver (success, vendor returns null, no driver on the classpath), `getPropertyInfo`, `acceptsURL(null)`, mixed-case sub-protocol on `vendorUrl()`.

## Proposed Solutions

### Option 1: Fix each edge in place (recommended)

**Approach:** Wrap `parse` in `getPropertyInfo` the way `connect` does; default null `info` to `new Properties()`; return false from `acceptsURL(null)`; build `vendorUrl` from the lower-cased sub-protocol token; copy `Properties` and remove `scalardbnative.*` keys before forwarding; catch the `getDriver` failure and rethrow `08001` with a message naming the sub-protocol and the vendor driver class expected on the classpath; prepend the driver's own `DriverPropertyInfo` entries (name, description, default, choices); add `deregister()`; read the version from the manifest `Implementation-Version` with the constants as fallback.

**Pros:**
- Small, local diffs; each one has a one-line test
- No new abstraction

**Cons:**
- The property list duplicates the design table until a constants class exists (todo 016 proposes one)

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Introduce a `DriverProperties` constants class first

**Approach:** Define every driver property (name, description, default, choices) in one class used by `getPropertyInfo`, by the forwarding filter and later by the connection proxy; then apply the fixes of Option 1 on top.

**Pros:**
- One source of truth for the design's property table and todo 016's error contract
- The connection proxy will need the same constants

**Cons:**
- Slightly larger change now for properties that do not exist yet

**Effort:** 3-4 hours

**Risk:** Low

---

### Option 3: Defer everything but the vendor URL case bug

**Approach:** Fix only `vendorUrl` casing now (the one verified user-visible failure) and leave the rest until the connection proxy lands.

**Pros:**
- Minimal

**Cons:**
- `getPropertyInfo` keeps violating the JDBC contract; pools that call it on a bad URL get an unchecked exception

**Effort:** 30 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:26-28, 32-38, 53-69, 78-85`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:41, 73`
- `driver/src/test/java/com/scalar/db/nativejdbc/NativeJdbcDriverTest.java`
- `driver/src/test/java/com/scalar/db/nativejdbc/NativeJdbcUrlTest.java`

**Related components:**
- Todo 015 (credentials in exception messages): the "no suitable driver" message must not include the URL
- Todo 016 (refusal error contract): the constants class for SQLSTATEs is a natural home for property names too
- `docs/design.md` section 1, "URL scheme" and the property list

**Database changes:** none

## Resources

- `docs/design.md` section 1 (Architecture, URL scheme, driver properties)
- JDBC 4.3 `java.sql.Driver` contract for `acceptsURL`, `getPropertyInfo`
- pgjdbc `org.postgresql.Driver.deregister()` as the reference pattern
- Review agents: feature-dev:code-reviewer (P3-12, P3-13), agent-native-reviewer (P3-3, P3-4), security-sentinel (P3-5), architecture-strategist (P3-7)

## Acceptance Criteria

- [ ] `getPropertyInfo("jdbc:scalardb-native:sqlite:x", null)` throws `SQLException` with SQLSTATE `08001`, not `IllegalArgumentException`
- [ ] `getPropertyInfo` with null `info` reaches the vendor driver with an empty `Properties`
- [ ] `getPropertyInfo` lists every driver property named in `docs/design.md` before the vendor's entries
- [ ] `acceptsURL(null)` returns false
- [ ] `NativeJdbcUrl.parse("jdbc:scalardb-native:MariaDB://h/db").vendorUrl()` equals `jdbc:mariadb://h/db`, asserted by a test
- [ ] `scalardbnative.*` keys are absent from the `Properties` the vendor driver receives, asserted with a stub vendor driver
- [ ] A missing vendor driver yields `08001` with a message naming the sub-protocol and the vendor driver, without the URL
- [ ] `NativeJdbcDriver.deregister()` exists and `DriverManager.getDrivers()` no longer lists the driver afterwards
- [ ] Tests cover `connect` success, vendor returning null, and no vendor driver on the classpath
- [ ] `./gradlew check` passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from feature-dev:code-reviewer (P3-12, P3-13), agent-native-reviewer (P3-3, P3-4), security-sentinel (P3-5) and architecture-strategist (P3-7)
- Verified the vendor URL casing bug by running `NativeJdbcUrl.parse` against the built classes
- Other items confirmed by code reading of `NativeJdbcDriver.java` and `NativeJdbcUrl.java`

**Learnings:**
- The URL test suite asserts the folded sub-protocol but never the vendor URL, which is why the casing bug passed CI

---

### 2026-10-09 - Partly resolved with 015

**By:** Claude Code

**Actions:**
- Done: getPropertyInfo's unchecked exception and null info, acceptsURL(null), the sub-protocol's case in the vendor URL, and the missing-driver message. Still open: listing and stripping the driver's own properties, deregister(), the version constants, and the connect-path tests with a real vendor driver.

### 2026-10-09 - Fix applied (plan milestone 0)

**By:** Claude Code

**Actions:**
- `DriverProperties` reads metadataSchema, catalogTtlMs, allowNoCatalog, inflightWaitMs and passthrough from the connect Properties and from the URL (`?k=v&...`, or `;k=v` on SQL Server; the URL wins); an unknown or malformed `scalardbnative.*` key fails connect with 08001. The driver's keys are stripped from the vendor URL and the vendor properties; `getPropertyInfo` lists the driver's properties, with descriptions and choices, ahead of the vendor's.
- `NativeJdbcDriver.deregister()` deregisters the driver and closes its catalogs. `NativeJdbcUrl.engine()` uses `Engine.of`.
- Not done: the version constants still mirror gradle.properties by hand (cosmetic).
- Tests: DriverPropertiesTest, NativeJdbcUrlTest.driverParameters_areTakenOutOfTheVendorUrl, DriverIntegrationTest.getPropertyInfo_listsTheDriversPropertiesFirst.

## Notes

- Keep the message fix coordinated with todo 015 so the URL is never echoed.
- If todo 016's constants class lands first, put the property names there rather than in the driver class.
