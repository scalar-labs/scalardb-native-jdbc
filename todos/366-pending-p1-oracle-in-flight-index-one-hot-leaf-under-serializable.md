---
status: pending
priority: p1
issue_id: "366"
tags: [oracle, correctness, performance, scalardb-side, design]
dependencies: []
---

# Oracle: the in-flight index is one hot leaf block, and SERIALIZABLE prepares fail on it

## Problem Statement

On Oracle every in-flight entry of a table lives in one leaf block of its in-flight index: in the
plain index the `(1, k)` and `(2, k)` entries sit together at its left edge, and the function-based
index of todo 364 holds nothing else. A transaction at Oracle's SERIALIZABLE that inserts or deletes
an entry in a leaf block that another transaction changed and committed after it began fails with
ORA-08177, whatever the rows. So concurrent ScalarDB prepares and commits on a table the setup has
indexed fail at a high rate for no row conflict. Measured on the container (23.26, 300,000-row
tables TI_P plain and TI_F function-based, 16 sessions at SERIALIZABLE, each 200 pairs of a prepare
of 10 rows disjoint from every other session's and its commit, scratchpad `ora/ser8177.jsh` and
`ser8177b.jsh`, 2026-10-31):

| configuration | prepares failing | commits failing |
|---|---|---|
| plain index, updates to state 1 | 53% | 23% |
| function-based index, updates | 52% | 21% |
| plain index, inserts of new rows | 60% | 23% |
| function-based index, inserts | 66% | 22% |
| no in-flight index at all | 0.4% | 1.6% |
| plain index `INITRANS 50` | 57% | 19% |
| function-based index `INITRANS 50` | 56% | 20% |
| plain index, 4 sessions | 52% | 32% |
| function-based index, 4 sessions | 55% | 33% |

Without the index the rate is the table's own; with either form it is the leaf's. `INITRANS` does
not matter, so this is not ITL shortage: Oracle's SERIALIZABLE check on an index leaf is for the
block. Found while reviewing todo 364's build: c7's sequential case (a prepare of 501 rows into the
function-based index's leaf, emptied by a few small transactions before it, fails where the plain
index's does not, 10 of 10 against 0 of 10, retriable) is the one place the form differs.

## Measured as a mixed workload (option 1, 2026-10-31)

The user's word ("yes": 366 first). On the container (`ora/ser366.jsh`), 300,000-row inline-shaped
tables: K ScalarDB-shaped writers at Oracle's SERIALIZABLE, each a loop of transactions preparing 3
random rows (state 3 to 1, one storage transaction) and committing them (1 to 3, another), paced to a
rate; beside them 4 driver-shaped writers at READ COMMITTED stamping other random rows at 50 a second
each (state 3 to 3, no index maintenance). The in-flight index: none (TI's made unusable), plain
(TI_P), function-based (TI_F). ORA-08177 as a share of the prepares, then of the commits:

| index | 4 sessions, 10/s each | 4 at 50/s | 4 unpaced | 16 at 10/s | 16 at 50/s | 16 unpaced |
|---|---|---|---|---|---|---|
| none | 0.0 / 0.0 | 0.3 / 0.0 | 0.2 / 0.2 | 0.3 / 0.1 | 0.1 / 0.7 | 0.3 / 0.4 |
| plain | 17.1 / 12.1 | 15.5 / 21.0 | 32.1 / 27.9 | 37.9 / 30.9 | 41.6 / 33.2 | 54.4 / 30.1 |
| function-based | 7.9 / 12.1 | 10.2 / 23.0 | 45.1 / 24.0 | 38.9 / 28.1 | 40.3 / 33.0 | 54.0 / 30.3 |

Four sessions at ten a second are thirty prepares a second on the whole table, and a fifth of the
ScalarDB transactions already retry. At READ COMMITTED (16 sessions, unpaced) both indexes give
0.0 / 0.0. The driver-shaped writers failed nowhere. The rate follows the committed changes to the
leaf within a SERIALIZABLE transaction's window, not the sessions: Oracle's check on an index block
is for the block.

Mitigations by the index's shape (`ora/ser366b.jsh`, `ser366c.jsh`, `ser366d.jsh`, the
function-based index on TI_F, the same writers): entries padded with a 3,500-byte trailing
expression, one or two a leaf, 0.8 / 0.8, 3.7 / 3.5, 14.3 / 5.7, 12.1 / 6.2 at 4 sessions 10/s, 4 at
50/s, 16 at 50/s, 16 unpaced (the setup's form in the same run 6.3 / 20.0, 14.8 / 22.3, 46.3 / 28.4,
56.1 / 28.2), the leaf splits' branch block being what remains; padded to 800 bytes, about six a
leaf, 15.8 / 9.2, 16.5 / 17.2, 24.0 / 28.3, 17.4 / 31.0, no better than the setup's form; a global
hash-partitioned index refuses an expression as its partitioning key (ORA-30555), and a partitioned
table is not the driver's to make. The probes' cost, gets a call at 38 and 380 rows in flight: the
gate 1 in every form (a PL/SQL loop over `v$mystat`; a JDBC `Statement` loop over `V$SQL` misread it
at 37); the keyed probe 2.9 in the setup's form, 5.0 padded (blevel 1); the state column's key set
2.1 in the setup's form, 24 and 216 padded to 3,500 bytes, 8 and 68 to 800: one block per two, or
per six, in-flight rows instead of one block.

## Who meets it

- ScalarDB's own storage connections where they run Oracle's SERIALIZABLE. The user docs ask
  `scalar.db.jdbc.isolation_level=REPEATABLE_READ` of every ScalarDB application at both driver
  levels on every engine (`transactions.md`'s table, `getting-started.md`, `setting-up-tables.md`),
  and on Oracle that value does not work at all: ojdbc accepts only READ_COMMITTED and SERIALIZABLE
  (ORA-17030 on any other), so Hikari fails to initialize the pool (measured 2026-11-01 with
  HikariCP 7.0.2 and ojdbc 23 as `JdbcUtils` configures them; scratchpad `ora/hik366.jsh`). What
  works on Oracle is `SERIALIZABLE`, which the fixture already uses (`Engines.ORACLE.isolation()`
  returns it, "Oracle has no REPEATABLE READ"), or `READ_COMMITTED`, or leaving it unset. ScalarDB
  itself runs READ COMMITTED when the setting is unset (`JdbcConfig.getIsolation()` is empty,
  `JdbcUtils` sets Hikari's level only from it; ScalarDB 3.20).
- ScalarDB requires the level at or above Oracle's minimum, 8, only for a virtual table, the
  decoupled layout: `JdbcAdmin.getStorageInfo` computes `isConsistentVirtualTableReadGuaranteed`
  from a connection's level against `RdbEngineOracle.getMinimumIsolationLevelForConsistentVirtualTableRead`;
  `ConsensusCommitAdmin` checks it when a decoupled table is created or imported, and
  `ConsensusCommitOperationChecker.throwIfOperationForVirtualTableButNotConsistentVirtualTableReadStorage`
  refuses every operation on a virtual table at runtime when it is false. An inline table has no
  such check.
- Not the driver's own transactions: 0 errors in every trial; its writes keep a row's state at 3 and
  touch no in-flight entry, and its probes read the leaf, which raises nothing.
- Not the benchmarks so far: no cell has a concurrent ScalarDB writer.

## Options

1. Inline layout on Oracle: ScalarDB's connections at `READ_COMMITTED`, ScalarDB's own default.
   0.0% in the control. The driver needs nothing from ScalarDB's level on Oracle: ScalarDB's storage
   calls are their own short transactions with conditional updates, its reads take no locks (design
   section 2, item 8), and Oracle gives a statement one snapshot at READ COMMITTED. A docs change:
   the Oracle rows of `transactions.md`'s isolation table and the sentences in `getting-started.md`
   and `setting-up-tables.md` say `READ_COMMITTED` (or unset) for an inline table and `SERIALIZABLE`
   for a decoupled one, since `REPEATABLE_READ` is refused by ojdbc; and the fixture's Oracle value
   (`Engines.ORACLE.isolation()`, `SERIALIZABLE` for both layouts today) becomes `READ_COMMITTED`
   for the inline layout, which also removes the ORA-08177 flaky of the inline imports in CI.
   Recommended.
2. Decoupled layout on Oracle: a ScalarDB-side change, `RdbEngineOracle`'s minimum level for a
   consistent virtual-table read from 8 to 2, since Oracle's READ COMMITTED already gives one
   statement one snapshot, which is all the virtual table's two-table read needs; one constant. With
   it, option 1 covers both layouts. Until then the rates above hold for a decoupled table with any
   in-flight index: document them with the figures, and point mixed workloads on Oracle at the inline
   layout. Recommended to raise with the ScalarDB team.
3. The index's shape as a stopgap for decoupled tables: padding to one or two entries a leaf cuts
   the rate four to ten times and costs the key set's probe one block per two in-flight rows (24
   gets at 38 rows, 216 at 380, against 2) and the keyed probes two gets more. Not recommended unless
   2 is refused; 800-byte padding, `INITRANS`, a reverse key and hash partitioning do not help.
4. Accept and document, the fallback of 2.

## Log

- 2026-10-31: found and measured during the review of todo 364's build (a9412f8); design section 3's
  function-based paragraph records the facts; todo 364's log has the review. Not this commit's: the
  plain index has it in the same measure.
- 2026-10-31 (later): option 1 measured on the user's word, the paced mixed workload above and the
  index-shape mitigations; the ScalarDB facts read from the 3.20 jar (`javap`); the recommendation
  sent to the user and to 6e for the benchmark doc. The user's call: the inline docs change (option
  1) and raising Oracle's minimum with the ScalarDB team (option 2).
- 2026-11-01: the user asked why READ COMMITTED is enough for ScalarDB, whether the Consensus Commit
  level sets the Oracle level (it does not: two unlinked settings), when the issue occurs (the JDBC
  level at Oracle SERIALIZABLE on a table with an in-flight index, whatever the Consensus Commit
  level or layout; the layout decides only whether it can be avoided), and for the options again;
  answered with a container demo (`ora/cas366.jsh`: the second prepare-shaped UPDATE gets 0 rows at
  READ COMMITTED and ORA-08177 at SERIALIZABLE, a reader's next statement sees the commit at both)
  and Oracle's Concepts guide. Found while drafting the feedback: ojdbc refuses `REPEATABLE_READ`
  (ORA-17030), so the docs' value fails on Oracle (above). The user takes option 2 to the ScalarDB
  team; the feedback written as a page (the Artifact "Oracle Virtual Table Isolation") and as text.
- 2026-11-01 (6e): the user's word, "For 1 (366), I will do both": option 1 (the inline docs change
  and the fixture's Oracle level at `READ_COMMITTED` for inline) and option 2 (the user takes
  `RdbEngineOracle`'s minimum to the ScalarDB team). Option 1 sent to 89 to design and route to c7.

## Design of option 1 (2026-11-01, for c7)

The user's word via 6e: "For 1 (366), I will do both". Option 2 is the user's with the ScalarDB team
(the feedback page). Option 1 is ours, docs and fixture, and stands whatever ScalarDB does: the docs
today prescribe a value that does not start on Oracle, and ScalarDB's fix only lifts the decoupled
requirement; Oracle users still have to choose READ_COMMITTED themselves.

Facts the design rests on (measured 2026-11-01, `ora/rr366.jsh`, `ora/hik366.jsh`; ScalarDB 3.20 by
`javap`): ojdbc accepts only READ_COMMITTED and SERIALIZABLE (`ORA-17030`), so
`scalar.db.jdbc.isolation_level=REPEATABLE_READ` makes HikariCP fail to initialize every ScalarDB pool
on Oracle (`JdbcUtils.createDataSource` sets the level on the transaction, table-metadata and admin
pools alike). ScalarDB requires its engine minimum, SERIALIZABLE on Oracle, only for a virtual table:
at `createTable` and `importTable` with decoupling and at every operation on one
(`ConsensusCommitOperationChecker`); an inline table has no requirement, and unset means Oracle's
READ COMMITTED. One `TransactionFactory` serves a test suite's tables of both layouts, so a suite
with a decoupled table on Oracle must run ScalarDB at SERIALIZABLE; `InlineIntegrationTest` is the
only suite whose tables are all inline (every other suite that imports or creates tables names
`transaction-metadata-decoupling`). `ReadOnlyReaderIntegrationTest`, `SerializableIntegrationTest`
and `MaelstromIntegrationTest` set the level themselves and do not run on Oracle.

### The docs

1. `docs/transactions.md`. The table (lines 26–31): in the rows for `TRANSACTION_READ_COMMITTED` and
   `TRANSACTION_REPEATABLE_READ` the third column reads `REPEATABLE_READ` for "all five"; it becomes
   `REPEATABLE_READ`, Oracle as [ScalarDB's connections on Oracle](#scalardbs-connections-on-oracle).
   Line 34, "ScalarDB needs at least `REPEATABLE_READ` there to create or import a table the driver
   serves.", becomes: ScalarDB needs at least `REPEATABLE_READ` there for a decoupled table the driver
   serves, to create or import it and for every transaction on it; an inline table has no such
   requirement. On Oracle the value depends on the layout (the new section). A new subsection
   `### ScalarDB's connections on Oracle` in the Oracle section, after "No SERIALIZABLE on Oracle",
   saying: `REPEATABLE_READ` is refused by the Oracle JDBC driver (`ORA-17030`, only `READ_COMMITTED`
   and `SERIALIZABLE` exist) and ScalarDB's connection pool fails to start with it. For inline tables
   set `READ_COMMITTED` or leave the setting unset, which is the same on Oracle: ScalarDB's own default,
   and every ScalarDB read is one statement, which Oracle's READ COMMITTED gives a consistent snapshot
   of. For decoupled tables ScalarDB requires `SERIALIZABLE`, its minimum for a virtual table on Oracle,
   to create or import the table and for every transaction on it; at that level, on a table with the
   setup's in-flight index, concurrent ScalarDB writes fail now and then with `ORA-08177`, which
   ScalarDB reports as a retryable conflict (measured on Oracle Free 23: four writers at ten prepares a
   second each, 8–17% of the prepares; sixteen unpaced writers, 40–50%; none at `READ_COMMITTED`); a
   request to lower the minimum is with the ScalarDB team. The setting is the level of ScalarDB's own
   connections only; the driver's connections set theirs with `setTransactionIsolation`.
2. `docs/getting-started.md` line 144–145: "ScalarDB needs `REPEATABLE_READ` here to create or import
   a table the driver serves." becomes: ScalarDB needs `REPEATABLE_READ` here for a decoupled table the
   driver serves (option C); on Oracle the value follows the layout, `READ_COMMITTED` for inline (A, B)
   and `SERIALIZABLE` for decoupled (C), as [ScalarDB's connections on
   Oracle](transactions.md#scalardbs-connections-on-oracle) says. The properties example (line 138) is
   PostgreSQL's and stays.
3. `docs/setting-up-tables.md` lines 54–56: "with `scalar.db.jdbc.isolation_level` as [Isolation
   levels](...) gives" gains ", on Oracle by layout ([ScalarDB's connections on Oracle](...))".
4. `docs/configuration.md` lines 123–124: "has which values go with each level" gains " and, on
   Oracle, with each layout".
5. `docs/design.md` section 3, the function-based paragraph's last sentence (line ~970), "which the
   user docs should ask for there" becomes "which the user docs ask for there (transactions.md,
   ScalarDB's connections on Oracle)"; section 1 item 8's sentence on the minimum per engine is
   already on main (this commit).

### The fixture

6. `Engines.java`. `isolation()` keeps its values: `REPEATABLE_READ`, Oracle `SERIALIZABLE`, since a
   suite with a decoupled table needs it there; Oracle's comment (line 138) says why: ojdbc accepts
   only `READ_COMMITTED` and `SERIALIZABLE`, and the suites' decoupled tables need `SERIALIZABLE`
   until ScalarDB's Oracle minimum drops, after which this returns `READ_COMMITTED` and `inlineIsolation`
   goes. New `String inlineIsolation()`: the level the docs give ScalarDB for a suite whose tables are
   all inline, `isolation()` on every engine but Oracle, `READ_COMMITTED` there. New `public
   TransactionFactory scalarDbInline()`: `scalarDb(Properties)` with `scalar.db.jdbc.isolation_level`
   set to `inlineIsolation()` (and the `Properties` form if a caller needs extras). `scalarDb(Properties)`
   unchanged.
7. `InlineIntegrationTest` takes `e.scalarDbInline()` for its admins and managers (the imports at
   lines 69, 181, 286 and the managers at 207, 250, every `e.scalarDb()` in the class), with a line
   saying it runs ScalarDB at the level the docs give for inline tables on each engine, `READ_COMMITTED`
   on Oracle. This is what puts the recommended Oracle configuration through CI. The `ORA-08177` import
   flaky of ScalarDB's own writes at SERIALIZABLE stays possible in the mixed suites until option 2.
8. Not changed: the benchmark harness's ScalarDB properties (6e's; the import runs once and the
   ScalarDB client is idle during a run; `READ_COMMITTED` for inline cells is 6e's call), the driver.

Checks: the docs build (links resolve: the new anchor `#scalardbs-connections-on-oracle`); unit tests;
`InlineIntegrationTest` on Oracle locally; spotless. Lands on the user's word in c7's session, rebased
onto main's head.

## Review record (2026-11-01, written down 2026-11-02 at c7's ask)

Option 1 as built by c7 on `oracle-366-docs-fixture`: 3ff8b73 (the docs by layout and the inline
suite at `READ_COMMITTED`, on 4d499bc) was reviewed from `git show` on 2026-11-01 and found
mergeable, read against the design above: the transactions.md table rows and line 34's
decoupled-only requirement, the new subsection and its anchor `#scalardbs-connections-on-oracle`,
the three one-clause pointers, design section 3's sentence, `Engines` (`isolation()` kept with its
comment, `inlineIsolation()`, `scalarDbInline()` through `scalarDb(Properties)`, whose `putIfAbsent`
leaves the level), and `InlineIntegrationTest`'s five call sites with the class comment; the
subsection's figures match the measurements above. One nit, design.md line 976 at 144 characters,
fixed by c7 as 89b49c2 (lines 976–977 rewrapped at 100, nothing else; the fence count 38 as on
main). Verdict for the pair 3ff8b73 + 89b49c2: mergeable. The verdict went to c7 by message and was
not written here until 2026-11-02, when c7 found no record of it before landing; it lands on the
user's word in c7's session, rebased onto main's head.

### 2026-11-02: option 1 on main (77ece28 + d67342c)

c7 rebased `oracle-366-docs-fixture` onto 0561b6a and fast-forwarded main on the user's "land it" in
its session; the range-diff against the reviewed 3ff8b73 + 89b49c2 shows both commits identical, and
design.md keeps its 38 fences. Nothing pushed. With this, the inline layout's recommended Oracle
configuration (ScalarDB's connections at `READ_COMMITTED`) is in the user docs and runs through CI
in `InlineIntegrationTest`. The todo stays open for the decoupled layout: option 2, ScalarDB
lowering `RdbEngineOracle`'s minimum to READ COMMITTED, is the ScalarDB team's.
