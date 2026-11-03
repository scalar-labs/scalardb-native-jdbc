# Brainstorm: ScalarDB Core hands out the native driver's connections

Date: 2026-11-03

## What we're building

An application that uses both kinds of transaction on the same database today has two of
everything. It uses ScalarDB for global transactions, through `scalardb.properties` and a
`TransactionFactory`. It uses the native JDBC driver for local transactions, through a hand-written
`jdbc:scalardb-native:...` URL, `scalar.db.native_jdbc.*` properties and its own connection pool.
It also has to pick two compatible library versions. The settings the two halves share (database,
credentials, ScalarDB's catalog schema, coordinator namespace, isolation levels) are written twice
and must agree.

ScalarDB Core takes the driver as a dependency and hands out its connections, built from the same
`scalardb.properties`:

- a pooled `DataSource` for a namespace: one configuration, one entry point;
- the derived driver URL and `Properties` for the same namespace, for an application that keeps its
  own pool (HikariCP, Spring's).

Local transactions stay native SQL over JDBC. Global transactions stay ScalarDB's. The two
programming models are not merged.

## Why this approach

- It removes the three pains the user named: two configurations, two setups or entry points, and
  two dependencies to version together.
- The isolation match between the two sides becomes enforced instead of documented. Today
  `docs/configuration.md` ("ScalarDB settings") asks the application to set
  `scalar.db.consensus_commit.isolation_level` and `scalar.db.jdbc.isolation_level` to match the
  driver's level by hand. A mismatch costs the guarantees between the two sides, not just
  convenience.
- The ScalarDB Cluster client SDK already depends on Core (`scalardb-cluster/client/build.gradle`,
  `api "com.scalar-labs:scalardb:..."`), so Cluster inherits the code when its turn comes.

Rejected along the way:

- **ScalarDB's Get/Put/Scan run through the driver** (for example, a `native-jdbc` transaction
  manager). Rejected because the driver exists for the database's own SQL.
- **A helper in this repo that reads `scalardb.properties`.** It needs no ScalarDB change, but
  ScalarDB would not bring the driver's version, and the application would still have two entry
  points.
- **Cluster first.** A Cluster client's configuration names the cluster, not the database, so the
  database settings would have to come from somewhere new.

## Key decisions

1. **Pains to remove:** two configurations, two setups or entry points, two dependencies. Not the
   two programming models.
2. **Where it lives:** ScalarDB Core for direct mode. The Cluster client SDK later; see the open
   questions.
3. **What Core hands out:** both a pooled `DataSource` and the derived URL plus `Properties`, keyed by
   namespace. With multi-storage, the namespace mapping picks the database. A namespace on a
   storage the driver does not support is refused. The pool's sizing settings go in
   `scalardb.properties`.
4. **Isolation:** derived and enforced. Core sets the driver's default level to the one that
   matches `scalar.db.consensus_commit.isolation_level`. A read-write transaction must run at that
   level. A read-only transaction may run at that level or at READ COMMITTED, chosen per transaction
   with `setReadOnly(true)` and `setTransactionIsolation(TRANSACTION_READ_COMMITTED)` before its
   first statement (with Spring, `@Transactional(readOnly = true, isolation = READ_COMMITTED)`). It
   cannot write ScalarDB tables (`25006`), so its level cannot weaken either side's writes. Any other
   combination fails. The check runs at the transaction's first statement, not inside the setters,
   because an application may call them in either order. On Oracle, `scalar.db.jdbc.isolation_level`
   depends on the table layout, and SERIALIZABLE is refused (`docs/transactions.md`, "ScalarDB's
   connections on Oracle"); the derivation follows that table.
5. **Retries:** nothing new. Local code keeps the driver's SQLStates, which JDBC frameworks already
   understand. A retry helper waits until users ask for one.
6. **Dependency shape:** the driver shades (relocates) JSQLParser inside its jar, and Core takes a
   normal dependency on it. No clash with an application's own JSQLParser (MyBatis-Plus,
   PageHelper), and ScalarDB brings the driver's version.
7. **Database user:** the local connections use ScalarDB's database user from `scalardb.properties`
   by default. Optionally, `scalardb.properties` names a separate user and password for the local
   connections only, so production deployments can run the application's own SQL without ScalarDB's
   administrator rights. Either user needs the grants in `docs/configuration.md` (catalog SELECT, the
   read views), which `scalardb-native-tools setup --grant <user>` gives as today.
8. **Both repositories change.** Core gains the entry points, the settings mapping and the
   isolation check. The driver gains the shaded JSQLParser and a way for Core to fix the allowed
   levels.

## Open questions

- **Cluster's database settings and security.** A Cluster client's `scalardb.properties` has no
  database settings, and a local transaction goes to the database directly, bypassing Cluster's
  authentication and ABAC. Should the database settings sit in the client configuration (the
  application holds database credentials) or come from the cluster (credential distribution)?
  Deferred to a follow-up by the user's choice.

## Resolved questions

- Who is annoyed, and by what: two configurations, setups and dependencies (2026-11-03).
- Cluster: Core first, Cluster later.
- Entry point: both a pooled `DataSource` and the derived URL plus `Properties`.
- Isolation: derive and enforce, with READ COMMITTED also allowed per read-only transaction.
- Database user: ScalarDB's by default, an optional separate one for the local connections.
- Retries: nothing for now.
- JSQLParser: shaded in the driver.
- Other applications on the same tables: an application that uses only ScalarDB still sets its
  isolation settings by hand, as the docs say today. No automatic check across applications.
- Release timing: Core brings a tested driver version. An application can take a newer driver with a
  one-line version override, and the docs say which driver versions work with each ScalarDB
  version.
- Driver-only settings (`catalog_ttl_millis`, `inflight_wait_millis`, `predicate_checks`): written
  in `scalardb.properties` under the same `scalar.db.native_jdbc.*` names, and Core passes them to
  the driver.
