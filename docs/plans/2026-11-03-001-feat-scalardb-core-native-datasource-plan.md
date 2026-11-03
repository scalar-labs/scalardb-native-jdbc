---
title: "feat: ScalarDB Core hands out the native driver's connections"
type: feat
status: active
date: 2026-11-03
origin: docs/brainstorms/2026-11-03-scalardb-core-integration-brainstorm.md
---

# feat: ScalarDB Core hands out the native driver's connections

## Overview

An application that runs local transactions in the database's own SQL through this driver, and
ScalarDB transactions through ScalarDB Core, today configures the database twice, builds two entry
points and versions two libraries together. This plan makes ScalarDB Core build the driver's
connections from the same `scalardb.properties`:

- `TransactionFactory.getNativeDataSource(namespace)`: a pooled `DataSource`;
- `TransactionFactory.getNativeConnectionSettings(namespace)`: the driver URL and `Properties`,
  for an application that keeps its own pool.

The driver stays in its own repository and jar. Core brings it as a runtime dependency. Both
repositories change (see brainstorm: docs/brainstorms/2026-11-03-scalardb-core-integration-brainstorm.md,
decision 8).

## Problem statement

The settings the two halves share must agree, and nothing checks that they do:

| Setting | ScalarDB key | Driver input today |
| --- | --- | --- |
| Database | `scalar.db.contact_points` | the URL, `jdbc:scalardb-native:` + the vendor URL |
| Credentials | `scalar.db.username`, `scalar.db.password` | `user`, `password` |
| Catalog schema | `scalar.db.system_namespace_name` (or the deprecated `scalar.db.jdbc.table_metadata.schema`) | `scalar.db.native_jdbc.metadata_schema` |
| Coordinator | `scalar.db.consensus_commit.coordinator.namespace` | `scalar.db.native_jdbc.coordinator_namespace` |
| Isolation | `scalar.db.consensus_commit.isolation_level` | `setTransactionIsolation`, matched by hand (`docs/transactions.md:16`) |

An isolation mismatch costs the guarantees between the two sides, not just convenience
(`docs/transactions.md:16-19`).

## Findings that shape the plan

Research turned up three facts that the brainstorm did not have.

1. **Core compiles to Java 8; the driver needs Java 11.** ScalarDB master (4.0.0-SNAPSHOT,
   `416cdc22f`) sets source and target 8 for every project
   (`buildSrc/src/main/java/com/scalar/db/JdkConfigurationPlugin.java:76-77`). The driver compiles
   with `--release 11` (`build-logic/.../java11-conventions.gradle.kts:12`). So:
   - Core must not compile against the driver. It doesn't need to: it builds a URL string and a
     HikariCP pool with the driver's class name as a string, the way `JdbcUtils` already does
     (`core/.../storage/jdbc/JdbcUtils.java:106`). The isolation rule travels as a driver property.
     Core takes a **runtime-only** dependency.
   - A Java 8 application then has a Java 11 jar on its classpath. Java 8's `DriverManager` loads
     every `java.sql.Driver` service at first use. If it stops at the first one that fails to load
     (not yet verified), drivers listed after ours would not register themselves. ScalarDB's own pools
     set the driver class explicitly and are not affected; an application's own `DriverManager`
     calls could be. To verify on Java 8, and fix in the driver (D3 below) unless ScalarDB 4.0
     raises its floor to Java 11.
2. **Core already has what the pool needs.** Core pools with HikariCP (`core/build.gradle:141`) and
   brings the vendor drivers: pgjdbc, `ojdbc8`, mssql-jdbc and MariaDB Connector/J, which ScalarDB
   also uses for MySQL with `permitMysqlScheme=true` (`RdbEngineMysql.adjustJdbcUrl`). The driver
   certifies MariaDB Connector/J for MySQL (`docs/design.md:328`). The driver's tests run `ojdbc11`,
   so `ojdbc8` needs a test run.
3. **"Global transaction" is taken in ScalarDB 4.0.** `GlobalTransactionManager`
   (`core/.../api/GlobalTransactionManager.java:34`) means multi-branch transactions. The new API
   says "native", never "local" or "global".

## Proposed solution

### Core API

```java
// com.scalar.db.service.TransactionFactory
public NativeDataSource getNativeDataSource(String namespace);
public NativeConnectionSettings getNativeConnectionSettings(String namespace);

// com.scalar.db.api (new)
public interface NativeDataSource extends javax.sql.DataSource, AutoCloseable {}
public final class NativeConnectionSettings {
  public String getUrl();
  public Properties getProperties(); // user, password, the driver's keys
}
```

`NativeDataSource` exists only so the caller can close the pool without Core exposing HikariCP,
which is an `implementation` dependency of Core. Each call builds a new pool, and the caller owns
it: call it once at startup (decision 3).

### Settings mapping

Core resolves the namespace to its storage's properties (with multi-storage,
`scalar.db.multi_storage.namespace_mapping`, else `default_storage`). It then derives:

| Driver input | Source |
| --- | --- |
| URL | `jdbc:scalardb-native:` + `rdbEngine.adjustJdbcUrl(contact_points)` without its leading `jdbc:` |
| `user`, `password` | `scalar.db.native_jdbc.username` / `.password` if set, else `scalar.db.username` / `.password` (decision 7) |
| `scalar.db.native_jdbc.metadata_schema` | `JdbcConfig.getMetadataSchema()`, which already resolves the deprecated key against `system_namespace_name` (`JdbcConfig.java:337-340`) |
| `scalar.db.native_jdbc.coordinator_namespace` | `scalar.db.consensus_commit.coordinator.namespace`, default `coordinator` |
| `scalar.db.native_jdbc.isolation_level` (new, D1) | `scalar.db.consensus_commit.isolation_level` (decision 4) |
| Pool sizing | `scalar.db.native_jdbc.connection_pool.{min_idle,max_total,connection_timeout_millis,idle_timeout_millis,max_lifetime_millis,keepalive_time_millis}`, the same suffixes as `scalar.db.jdbc.connection_pool.*` (decision 3) |
| Other driver keys | every other `scalar.db.native_jdbc.*` key, passed through (resolved question: driver-only settings) |

Core strips its own keys (`username`, `password`, `connection_pool.*`) before passing the rest,
because the driver refuses an unknown `scalar.db.native_jdbc.*` key
(`DriverProperties.java:180`). With multi-storage, a per-storage value is written
`scalar.db.multi_storage.storages.<name>.native_jdbc.username` and arrives as
`scalar.db.native_jdbc.username`, because `MultiStorageConfig` strips the storage prefix.

### Refusals

`getNativeDataSource` and `getNativeConnectionSettings` throw `IllegalArgumentException` with a
`CoreError` message, at the call, when:

- the transaction manager is not `consensus-commit` (this covers ScalarDB's `jdbc` manager, which
  writes no metadata, and a Cluster client configuration, deferred by the brainstorm);
- the namespace's storage is not `jdbc`, or its engine is not PostgreSQL, MySQL, MariaDB, SQL
  Server or Oracle (ScalarDB's other JDBC engines: SQLite, YugabyteDB, Db2, Spanner, AlloyDB);
- the URL goes through the AWS Advanced JDBC Wrapper, which the driver doesn't certify;
- the level is SERIALIZABLE on Oracle (`Proxies.java:460`, the driver refuses it);
- the level is SERIALIZABLE on PostgreSQL and `scalar.db.jdbc.isolation_level` is not
  `SERIALIZABLE` (`docs/transactions.md:30`). The other storage-level minimums, including
  Oracle's per-layout one, are already checked by ScalarDB itself for virtual tables. The driver's
  level on Oracle depends on no layout. This refines brainstorm decision 4's Oracle note: Core adds
  nothing for Oracle beyond the SERIALIZABLE refusal;
- the runtime is older than Java 11;
- `scalar.db.native_jdbc.isolation_level` is set in the configuration: Core derives it from
  `scalar.db.consensus_commit.isolation_level`, and a value of the application's own could override
  the match (decision 4).

Core doesn't run `scalardb-native-tools setup`. Without the setup, the first connection fails with
the driver's own errors (`42501`, `55000`, `docs/configuration.md:105-112`).

## Implementation phases

### Phase 0: decisions with the ScalarDB team

- [ ] The Java floor: does 4.0 raise Core to Java 11? If yes, D3 is dropped.
- [ ] The API names and their package (`TransactionFactory`, `NativeDataSource`,
      `NativeConnectionSettings`).
- [ ] Release coupling: Core pins a tested driver version; an application may override it
      (resolved question: release timing).
- [ ] `scalar.db.native_jdbc.*` keys in ScalarDB's configuration space.

### Phase 1: the driver (this repository), released first

- [ ] **D1, `scalar.db.native_jdbc.isolation_level`** (`READ_COMMITTED`, `SNAPSHOT` or
      `SERIALIZABLE`). Unset, nothing changes. Set:
  - connections start at that level instead of SNAPSHOT (`Router.java:570`);
  - `levelSupported()` (`Router.java:2559`), which already runs at each statement on a ScalarDB
    table (`Router.java:1027` writes, `:1795` reads), also requires the configured level, or READ
    COMMITTED when the connection is read-only. Any other level fails with `0AN01`. Because the
    check runs at the statement, the order of `setReadOnly` and `setTransactionIsolation` doesn't
    matter (decision 4);
  - statements on ordinary tables are unaffected, as today;
  - docs: `configuration.md` (the key), `transactions.md` (the rule), `errors.md` (the message).
- [ ] **D2, JSQLParser shaded.** Relocate `net.sf.jsqlparser` into
      `com.scalar.db.nativejdbc.shaded.jsqlparser` in the published jar and drop it from the POM
      (decision 6). `SqlAst.java:1005-1007` compares class names as strings: the relocation must
      rewrite them, so a test must cover them (a CREATE, ALTER and DROP on a ScalarDB table still
      refused). Ship JSQLParser's licence; it is dual LGPL-2.1/Apache-2.0, and we take Apache-2.0.
      Remove the README's pinning warning. Check that `shell` and `tools` still build against the
      relocated jar.
- [ ] **D3, safe on a Java 8 classpath** (only if Phase 0 keeps Java 8). First, a test on Java 8
      that shows whether our service entry stops other drivers from registering. If it does,
      register an entry class compiled with `--release 8` that accepts no URL below Java 11 and
      delegates to `NativeJdbcDriver` otherwise.
- [ ] Test runs with Core's vendor drivers: `ojdbc8`, and MariaDB Connector/J for a `jdbc:mysql:`
      URL with `permitMysqlScheme=true`.

### Phase 2: ScalarDB Core

- [ ] `runtimeOnly "com.scalar-labs:scalardb-native-jdbc:<version>"`: no compile dependency.
- [ ] The settings mapping and refusals above, unit-tested from `Properties` alone (no database).
- [ ] `getNativeDataSource` builds a `HikariDataSource` with the driver class name as a string,
      following `JdbcUtils.initDataSource`.
- [ ] Integration tests in Core's per-engine JDBC suites (PostgreSQL, MySQL, MariaDB, SQL Server,
      Oracle): see the scenarios below. ScalarDB builds with JDK 8 (its root `build.gradle`), whose
      compiler cannot read the driver's Java 11 classes, and the feature refuses a runtime older
      than Java 11. So the tests:
  - run the driver's setup on the test database through reflection on the driver's public
    `Setup.run(...)` (`driver/.../setup/Setup.java:188`) or through the `scalardb-native-tools`
    command, never by compiling against the driver;
  - run on a Java 11+ runtime with ScalarDB's existing option
    `-PintegrationTestJavaRuntimeVersion` (`JdkConfigurationPlugin.java:40-44`).

### Phase 3: documentation

- [ ] ScalarDB docs: a page on native SQL alongside ScalarDB transactions (the API, the keys, the
      isolation rule, the grants and the setup). It also tells operators of ScalarDB-only
      applications on the same tables to set their isolation by hand (resolved question: other
      applications).
- [ ] This repository: `getting-started.md` gains the Core route; `configuration.md` points to it;
      a compatibility table of driver versions per ScalarDB version (resolved question: release
      timing).

## System-wide impact

- **Call path:** `getNativeDataSource` → HikariCP → `NativeJdbcDriver.connect` → the vendor driver
  Core already brings → the driver's catalog read in the system namespace. ScalarDB's own pools are
  separate and unchanged.
- **Errors:** configuration problems fail at the call with `IllegalArgumentException`. Everything at
  run time is the driver's SQLStates, unchanged; no retry helper (decision 5).
- **State:** each pool belongs to its caller. Core keeps no reference, so a caller that never closes
  it leaks connections. That is documented, not managed.
- **Other entry points:** `TwoPhaseCommitFactory` and `StorageFactory` get nothing until someone
  asks for it. The Cluster client SDK inherits the methods; on a Cluster configuration they refuse
  (the transaction manager isn't `consensus-commit`) until the deferred question is answered.
- **Compatibility:** without a call to the new methods, nothing changes for any Core user, apart from
  the driver jar on the runtime classpath (D3).

### Integration test scenarios

1. One `scalardb.properties`: a ScalarDB transaction and a native SQL transaction through
   `getNativeDataSource` write the same rows. Each side sees the other's committed writes and
   neither breaks the other's conflict detection.
2. `isolation_level=SNAPSHOT`: a read-write native transaction at READ COMMITTED fails with `0AN01`
   at its first ScalarDB-table statement; the same transaction read-only succeeds, whether
   `setReadOnly` came before or after `setTransactionIsolation`.
3. Multi-storage: two namespaces on two JDBC storages get pools on the right databases; a namespace
   on a non-JDBC storage is refused.
4. `scalar.db.native_jdbc.username` set: native SQL runs as that user, and ScalarDB keeps its own.
5. `getNativeConnectionSettings` fed to an application's own HikariCP gives the same behaviour as 1.

## Acceptance criteria

- [ ] An application configures the database once, in `scalardb.properties`, and needs no
      `jdbc:scalardb-native:` URL of its own.
- [ ] A read-write native transaction cannot run at a level other than the one matching
      `scalar.db.consensus_commit.isolation_level`; a read-only one can also use READ COMMITTED.
- [ ] The driver's published POM has no JSQLParser dependency, and an application with another
      JSQLParser version on its classpath works.
- [ ] Core has no compile-time reference to the driver; Core's Java 8 build is unchanged.
- [ ] A Java 8 application with Core 4.0 on its classpath loads its other JDBC drivers as before.
- [ ] Every refusal above has a unit test, and scenarios 1–5 pass on the five engines.

## Risks

- **The Java 8 floor.** If ScalarDB keeps Java 8 and D3 proves hard, Core can only offer this behind
  an application-added dependency, which reintroduces "two dependencies" (brainstorm decision 1).
- **Shading and string class names** (D2): a missed string turns a refused DDL into a passed-through
  one. The test in D2 guards that.
- **Release coupling:** the driver changes far faster than Core releases. The override and the
  compatibility table carry it (resolved question).
- **`ojdbc8`:** untested by the driver today.

## Out of scope

- The ScalarDB Cluster client's database settings and security (brainstorm open question, deferred).
- A retry helper, automatic checks across applications, running the setup from Core (brainstorm
  decisions 5 and resolved questions).

## Sources

- **Origin brainstorm:** [docs/brainstorms/2026-11-03-scalardb-core-integration-brainstorm.md](../brainstorms/2026-11-03-scalardb-core-integration-brainstorm.md).
  Carried forward: Core builds the connections from one `scalardb.properties` (decisions 1–3); the
  level is matched and enforced, with READ COMMITTED for read-only transactions (decision 4);
  JSQLParser is shaded (decision 6); an optional separate database user (decision 7).
- ScalarDB, at `416cdc22f` (origin/master, 2026-11-02): `core/.../service/TransactionFactory.java`,
  `core/.../storage/jdbc/JdbcUtils.java:83-123`, `JdbcConfig.java:26-37,337-340`,
  `ConsensusCommitConfig.java:24-25`, `MultiStorageConfig.java:20-25`,
  `RdbEngineMysql.java:520`, `buildSrc/.../JdkConfigurationPlugin.java:76`, `core/build.gradle:141-162`.
- This repository: `driver/.../jdbc/Router.java:570,1027,1795,2559`, `jdbc/Proxies.java:448-480`,
  `DriverProperties.java:23-30,180`, `sql/SqlAst.java:1005`, `docs/transactions.md`,
  `docs/configuration.md`, `docs/design.md:328`.
