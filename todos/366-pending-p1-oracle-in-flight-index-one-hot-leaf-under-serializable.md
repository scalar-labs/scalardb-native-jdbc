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
  and Oracle maps that to SERIALIZABLE (`transactions.md`, Oracle). ScalarDB itself runs READ
  COMMITTED when the setting is unset (`JdbcConfig.getIsolation()` is empty, `JdbcUtils` sets
  Hikari's level only from it; ScalarDB 3.20).
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
   section 2, item 8), and Oracle gives a statement one snapshot at READ COMMITTED. A docs change,
   the Oracle rows of `transactions.md`'s isolation table and the sentences in `getting-started.md`
   and `setting-up-tables.md`, and the Oracle fixture (`ReadOnlyReaderIntegrationTest`,
   `SerializableIntegrationTest` set `REPEATABLE_READ`). Recommended.
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
