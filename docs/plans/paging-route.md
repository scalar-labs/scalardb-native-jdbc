# Plan: the per-row route for key-ordered paging (todo 296)

## Problem

A page over a key order, such as the newest 20 orders with their customers, reads about 20 rows as a
plain query: the key index backwards, stopped at the limit. The split read (design 4.2) repeats it
per combination of committed and in-flight copies, and every copy with a table in flight starts from
the in-flight index. It reads all rows in flight, joins and sorts them to keep 20. At 10,000 rows in
flight that is 27-29x on Oracle and 105-119x on MySQL, against about 1x for the database alone with
the same rows held by open transactions (docs/benchmarks/read-overhead.md, 2026-10-20). The OFFSET
form is 13.8x and 64x even with nothing in flight.

The per-row form, each ScalarDB table read as one derived table with a left join on its metadata
and a CASE per column, keeps the plain plan: 0.9-1.1x at 100 and 10,000 rows in flight on both
engines. But it hides every non-key column from its index, so it costs 11-830x on a filter, join or
aggregate over one. The route must therefore be narrow.

## The rule

A read takes the per-row route when all of these hold, and the split otherwise:

1. **One SELECT**: no set operation, no WITH, no subquery that reads a ScalarDB table.
2. **A row limit**: LIMIT, FETCH FIRST, TOP or ROWNUM, with no offset (see the measurements).
3. **ORDER BY the key of one ScalarDB table** (the driving table): its full primary key, or a
   leading part of it, in one direction. Expressions are not accepted.
4. **No condition on a non-key column of a ScalarDB table**, in WHERE or in ON. Conditions on key
   columns are allowed, for example keyset paging (`WHERE id < ?`), and so are conditions on plain
   tables.
5. **Every other ScalarDB table is joined through its full key**: an equality from each of its key
   columns to a column of a table already joined. Inner joins only in the first version.
6. **No GROUP BY, HAVING, DISTINCT, aggregate or window function.**
7. **No anchoring, no states**: a read at READ COMMITTED that records no versions. A locking read,
   the SNAPSHOT and SERIALIZABLE checks, and reads that record versions keep their current paths.
8. **Decoupled layouts only.** The inline layout (milestone 10) reads its metadata from the row
   itself, so its form is different.

## Measured so far (Oracle, MySQL, 2026-10-20)

`VIEW_CASE` against the current DRIVER on every shape, median of three runs, ratio to BASE at 100 /
10,000 rows in flight:

| shape | Oracle DRIVER | Oracle VIEW_CASE | MySQL DRIVER | MySQL VIEW_CASE |
|---|---|---|---|---|
| page: JOIN FK, 20 rows | 2.6 / 38.4 | 1.1 / 1.3 | 5.6 / 171 | 1.2 / 1.3 |
| page: 20 rows of 500k | 0.8 / 1.7 | 0.6 / 2.0 | 1.8 / 29.2 | 1.1 / 1.0 |
| page: indexed range of 30k rows, 20 rows | 1.2 / 2.9 | 1.0 / 1.2 | 2.1 / 8.7 | 1.0 / 1.3 |
| page: 20 rows after 10k (OFFSET) | 17.2 / 15.9 | 41.5 / 32.0 | 55.0 / 15.3 | 4.0 / 4.1 |
| key lookups, key joins, composite-key lookups | 0.7-2.2 | 0.9-1.3 | 0.8-4.2 | 0.6-2.1 |
| any filter, join or GROUP BY on a non-key column | 0.4-4.9 | 2.6-2980 | 0.7-45 | 5.6-9209 |
| full scans and aggregates | 1.2-12.1 | 2.6-11.2 | 1.0-9.1 | 5.6-10.2 |

- Rule 4 holds: every shape with a condition on a non-key column loses, by 3x up to 9,000x. The one
  exception is the indexed range that keeps a key order ("region < ?", 30% of the rows match), at
  1.0-1.3x: a filter the key walk satisfies quickly. The first version still refuses it.
- OFFSET: the per-row form reads the skipped rows with a metadata lookup each. MySQL 4x against
  15-55x, but Oracle 32-41x against 16-17x. So a row limit with an offset other than 0 keeps the
  split until the other engines are measured.

## The emitted SQL

Each ScalarDB reference becomes a derived table under the reference's name or alias, holding the
committed image row by row. This is the same image as `rc_T`, written as one arm:

```sql
(SELECT t.id,
        CASE WHEN m.tx_state IN (1, 2) THEN m.before_customer_id ELSE t.customer_id END AS customer_id,
        ...
 FROM ns.o t LEFT JOIN ns.o_tx_metadata m ON m.id = t.id
 WHERE m.tx_state IS NULL OR m.tx_state = 3
    OR (m.tx_state IN (1, 2) AND (m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL))) b
```

The filter is written without `COALESCE`, which `rc_T` uses: PostgreSQL cannot estimate
`COALESCE(m.tx_state, 3) = 3`, takes the view for 0.5% of its rows, and reads and sorts both tables in
full instead of walking the key (page JOIN FK 218-870x, three tables 1053-1451x). Written plainly, it
estimates from the statistics of `tx_state` and keeps the walk: 0.9-1.1x (2026-10-20, three runs at
100 and 10,000 rows in flight, rows equal to BASE). Whether `rc_T` gains the same is open.

The key columns stay plain (`t.id`), so the ORDER BY and the key joins keep their indexes. The rest
of the statement is left as written: `SqlAst.joinTokens(Naming)` already replaces references and
gives an unaliased one its own name, as the `rc_T` fallback uses it. If a derived table cannot go
where a name goes, a CASE view per table created by the setup is the alternative (a schema change,
so second choice).

## Steps

1. **Bound the rule by measurement.** The `VIEW_CASE` run on every shape at 100 and 10,000 rows in
   flight is running now on Oracle and MySQL. Run it on PostgreSQL, MariaDB and SQL Server too. The
   route is enabled per engine, only where the paging shapes win and the plans show the early stop.
   Add keyset paging (`WHERE id < ? ORDER BY id DESC`) and a three-table page to the benchmark,
   since real paging looks like that.
2. **The detector**: `SqlAst.pagedByKey(keyOf)` returns the driving reference or null, following
   rules 1-6 above. Unit tests for each rule, positive and negative, on the five dialects' limit
   syntaxes.
3. **The emitter**: `SplitRead.perRow(...)`, or a small class beside it, builds the derived tables
   from `TableInfo` and rewrites through `joinTokens`. `Router.read` tries it before the split when
   rules 7 and 8 hold.
4. **Correctness tests** (integration, five engines): rows equal to the split read and to BASE with
   prepared updates, prepared deletes (state 2), in-flight inserts (hidden), backfilled rows
   (`before_tx_version` 0) and ascending and descending orders. Then the same shapes through T1/T2's
   differential runs once they exist.
5. **Performance**: the benchmark's DRIVER on the paging shapes within 1.2x of BASE_OPEN at 10,000
   rows in flight on the enabled engines, and every other shape unchanged against the run before.

## Not in the first version

- LEFT JOIN to a ScalarDB table reached by key: the per-row form handles it naturally, but the
  unmatched rows need their own tests.
- Locking reads, SNAPSHOT/SERIALIZABLE and anchored reads: they keep the split, or the locking
  path, until their checks are written for this form.
- An ORDER BY on a non-key column with an index (for example `ORDER BY created_at DESC LIMIT 20`):
  the CASE hides that column too, so it needs a different form, which would have to be measured.

## Coordination

The other session is changing `Router` and `SplitRead` for the inline layout (milestone 10). The
detector (step 2) lives in `SqlAst` and touches neither, so it can go first. The route itself (step
3) is a few dozen lines in `Router.read` and should land after milestone 10's read changes, or be
agreed with that session.

## Effort

Measurement half a day; detector and unit tests one day; emitter, routing and integration tests one
to two days; benchmark verification half a day.
