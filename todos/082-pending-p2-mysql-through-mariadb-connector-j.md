---
status: pending
priority: p2
issue_id: "082"
tags: [testing, mysql, compatibility]
dependencies: []
---

# Certify MySQL through MariaDB Connector/J

## Problem Statement

The design now certifies MySQL through both Connector/J and MariaDB Connector/J (design section 1, URL scheme), and the shell bundles only the second (docs/plans/shell.md, decision 3). The integration tests run MySQL through Connector/J only.

## Findings

- ScalarDB reaches MySQL only through MariaDB Connector/J, appending `permitMysqlScheme=true` to the URL (`RdbEngineMysql`); the driver passes the key through as a vendor property.
- Where the libraries may differ for the driver: the SQLSTATE and exception class around errors 1213 and 1205, `getCatalog` and the metadata calls, `Statement.cancel`.
- Both drivers are on the integration test classpath (`libs.mysql.connector.j`, and `libs.scalardb` brings MariaDB Connector/J), and both accept `jdbc:mysql:...?permitMysqlScheme=true`, so the configuration must make sure MariaDB Connector/J serves it.

## Proposed Solutions

### Option 1: A seventh engine configuration

**Approach:** `MYSQL_MARIADB_DRIVER` in `testing/Engines.java`: the `mysql:8.4` container, URLs with `permitMysqlScheme=true`, Connector/J deregistered from `DriverManager` for the run (or the configuration run in its own test task without Connector/J on the classpath); selectable as `mysql_mariadb_driver` through `-Dnativejdbc.engines`; README's list updated.

**Pros:** Every integration test, the interleaving matrix included, covers the combination.

**Cons:** One more MySQL container run in `check`.

**Effort:** Small

**Risk:** Low

## Recommended Action

Option 1. Any difference found goes into the design's per-engine table.

## Acceptance Criteria

- [ ] All integration tests pass on `mysql_mariadb_driver`, with a check that MariaDB Connector/J served the connections.

## Work Log

### 2026-10-11 - Raised

**By:** Claude Code

- From the shell plan's decision to bundle MariaDB Connector/J for MySQL.

## Resources

- `docs/design.md` section 1, URL scheme; `docs/plans/shell.md` decision 3.
