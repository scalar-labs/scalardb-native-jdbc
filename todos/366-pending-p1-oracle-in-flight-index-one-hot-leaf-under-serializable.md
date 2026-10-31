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

## Who meets it

- ScalarDB's own storage connections on Oracle, which run SERIALIZABLE: the least ScalarDB accepts
  for a virtual table there (`RdbEngineOracle.getMinimumIsolationLevelForConsistentVirtualTableRead`
  returns 8, ScalarDB 3.20), and the level the test fixture's ScalarDB prepares ran at when they
  raised c7's error. ScalarDB retries a prepare conflict, so the cost is the retry rate, not
  correctness, and its reads take no locks (design section 2, item 8).
- Not the driver's own transactions: its writes keep a row's state at 3 and so touch no in-flight
  entry in either form (an update that leaves an indexed column's value as it was maintains no
  index); its probes read the leaf, which raises nothing.
- Not the benchmarks so far: no cell has a concurrent ScalarDB writer, so the AWS runs could not
  show it.

## Options

1. Measure it where it matters: a mixed workload with concurrent ScalarDB writers on Oracle (the
   fixture's `scalar.db.jdbc.isolation_level`), with and without the setup's index, counting
   ScalarDB's retries. Nothing of the kind has run.
2. Nothing in the index's shape helps at these sizes: a few hundred entries fill one leaf whatever
   their order, a reverse-key index does not spread two state values, and a hash-partitioned global
   index needs the Partitioning option.
3. If ScalarDB's storage connections could run READ COMMITTED on Oracle (its `getMinimum...` is 8
   because the virtual table's two-table read needs one snapshot for both tables; Oracle gives a
   statement one snapshot at READ COMMITTED already, design section 2), the failures go away
   entirely. That is a ScalarDB-side question for the user.
4. Accept and document: `transactions.md`'s note on ScalarDB's connections would say that
   ScalarDB's prepares on an Oracle table the driver serves conflict falsely under concurrency and
   are retried.

The priority is the user's call; p1 because it affects every ScalarDB writer on Oracle once a table
is set up, not only the benchmark.

## Log

- 2026-10-31: found and measured during the review of todo 364's build (a9412f8); design section 3's
  function-based paragraph records the facts; todo 364's log has the review. Not this commit's: the
  plain index has it in the same measure.
