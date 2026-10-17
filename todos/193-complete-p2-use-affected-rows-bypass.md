---
status: complete
priority: p2
issue_id: "193"
tags: [code-review, correctness, jdbc, mysql, mariadb]
dependencies: []
---

# The useAffectedRows refusal can be bypassed

## Problem Statement

NativeJdbcDriver.requireRowsMatched (~151-171) scans the URL with `[?&]useAffectedRows=`. Verified
with the real drivers (Connector/J 26.7.0, MariaDB 3.5.10) against the m8 containers that each of these
passes the check and yields affected-rows counts:

- MySQL percent-encoded key `?use%41ffectedRows=true` (Connector/J decodes keys).
- MySQL host-part properties `jdbc:mysql://(host=h,port=p,useAffectedRows=true)/db` and
  `address=(host=h)(port=p)(useAffectedRows=true)`.
- MariaDB: URL true with properties false: the driver lets the properties win, MariaDB lets the URL win.

Also refused needlessly: MySQL `no`, MariaDB `0` (both mean false).

## Proposed Solutions

### Option 1
Check on the opened connection: `UPDATE` a row to its own value (or a driver-owned probe) and require
count 1; or `SELECT ROW_COUNT()` semantics. Independent of URL syntax.

### Option 2
Ask the vendor driver: `Driver.getPropertyInfo(url, props)` for the effective value.

## Acceptance Criteria

- [x] Every spelling above is refused; false/no/0 accepted
- [x] Integration test on MySQL and MariaDB

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.

### 2026-10-17 - Fixed

**By:** Claude Code
- NativeJdbcDriver.requireRowsMatched asks the vendor driver (getPropertyInfo), which applies its own spellings and precedence; verified against Connector/J 26.7 and MariaDB 3.5.10 for each bypass; true only is refused. Integration test (properties and URL); the unit test moved there.
