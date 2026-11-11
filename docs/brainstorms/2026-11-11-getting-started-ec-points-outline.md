# Getting started, new scenario: an EC wallet and a point system (outline)

Replaces the orders/warehouse story in `docs/getting-started.md` and `samples/getting-started`.
`samples/demo` follows once the guide is settled.

## The scenario

Two companies' worth of systems, each with its own team, database and application, like a payment
app's wallet and its point program:

- **EC system** (database `ec`): customers top up a prepaid wallet and buy items with it.
  - `wallet(customer_id PK, balance)`: yen, INT
  - `orders(order_id PK, customer_id, item, price, paid_money, paid_points)`
  - `topup_history(id PK, customer_id, amount, at)`: only the top-up writes it
- **Point system** (database `point`): customers earn points (campaigns, check-ins).
  - `point_account(customer_id PK, points)`
  - `point_history(id PK, customer_id, delta, reason)`

The goal: let customers pay with points at checkout, with the rest from the wallet, **without
merging the two systems**. The point team keeps its database and application, and the EC app reaches
the point database only through ScalarDB.

Two phases:

1. **Adopt, no code change.** Import the shared tables into ScalarDB. Both apps change only their
   JDBC URLs and keep their SQL and local transactions.
2. **Integrate.** The EC app adds "pay with points" as one global transaction, in SQL too: through
   the ScalarDB PostgreSQL frontend (standalone server on ScalarDB Core) with the PostgreSQL JDBC
   driver. Its other paths stay local through the native driver. Both paths are SQL over JDBC; only
   the URL says local or global.

The rule the guide teaches: **a table becomes a ScalarDB table only if a global transaction touches
it.** `wallet`, `orders`, `point_account` and `point_history` do. `topup_history` doesn't, so it
stays ordinary.

Diagram: two database containers, the frontend, two apps. The EC app has two arrows (native driver
to `ec`; pgjdbc to the frontend, which reaches both databases through ScalarDB), and the point app
has one (native driver to `point`).

## What you need

As today. Phase 2's frontend is the image `ghcr.io/scalar-labs/scalardb-postgres-frontend:3.20.1`,
pulled by `docker run`, so nothing more to download. The example uses only JDBC drivers: the native driver and pgjdbc.

## 1. Start the two databases

- `docker network create ecpt`, then two `docker run --network ecpt` commands: `native-ec` on 5432
  and `native-point` on 5433, plus two readiness waits. The network lets step 5's frontend container
  reach both databases by name. Both created with the `C` collation, which the frontend checks for at startup
  (`backend_collation_check`).
- `psql -f ec.sql` and `psql -f point.sql` create the "existing" tables and data: customer 1 with
  wallet 10,000 and points 500.

## 2. Make the shared tables ScalarDB tables

- Import `wallet` and `orders` with `ec.properties`, and the Coordinator table there too (the EC app
  starts global transactions).
- Import `point_account` and `point_history` with `point.properties`.
- Layout: inline import only (today's B). Decoupled moves to a linked page for now; today's A is
  dropped since the tables already exist.
- `topup_history` is not imported: say why in one sentence, pointing to step 7.

## 3. Set both databases up for the driver

`scalardb-native-tools setup` and `check`, once per URL. Keep the `--no-wait` and backfill notes
from today's step 3, written once, not per database.

## 4. Phase 1: the apps as before

- `snsql` on `ec`: `SELECT * FROM wallet`, a short `BEGIN … COMMIT`, and the rewrite note.
- Run `EcApp topup 3000` and `EcApp buy book 1200` (one local transaction each, through the
  driver).
- Run `PointApp earn 100 checkin` (one local transaction on `point`).
- Show the code: the only change from the vendor driver is the URL.

## 5. Phase 2: pay with points

- `frontend.properties`: multi-storage, storages `ec` and `point`, namespaces mapped, Coordinator
  in `ec`, the same consensus-commit isolation as the driver's connections.
- Start the frontend: `docker run -d --network ecpt -p 127.0.0.1:15432:5432 -v
  "$PWD/frontend.properties:/scalardb-postgres-frontend/scalardb.properties:ro"
  ghcr.io/scalar-labs/scalardb-postgres-frontend:3.20.1`. Contact points name the containers
  (`native-ec:5432`, `native-point:5432`). One sentence on no authentication on Core (hence the
  loopback-only port), and on ScalarDB Cluster running it in each node for production.
- `EcApp buy-with-points book 1500 400`: `jdbc:postgresql://localhost:15432/ec`, then
  `SELECT ... FOR UPDATE` on `wallet` and `point.point_account`, two `UPDATE`s, two `INSERT`s,
  `COMMIT`. Put it beside the local `buy`: same JDBC code, different URL, one more database.
- Check both databases with `snsql`: the points and the wallet moved together.
- One sentence: ScalarDB aborts on insufficient points or balance, and nothing moves on either side.

## 6. A local and a global transaction on the same row

Two terminals, no load program:

1. psql to the frontend: `BEGIN; SELECT points FROM point.point_account WHERE customer_id = 1;`
   (420)
2. snsql to `point`: the point app's earn, `UPDATE … SET points = points + 10`, commits (430).
3. Back in psql: `UPDATE … SET points = points - 10; COMMIT;` fails with SQLState 40001
   (DB-CORE-20013). It would have written 410 over the earn.
4. `SELECT points` shows 430: the earn is kept. Retry the purchase and it commits from 430.

The point: the point app's local transaction and the EC app's global one stay consistent on the same
row, and the loser is told to retry. Checked on the containers 2026-11-11.

## 7. Optional: an ordinary table in a local transaction

Add `topup_history` to `EcApp topup`'s transaction (as today's step 6 adds `receipt`). One sentence:
the point side works the same way for its own ordinary tables.

## Start over and clean up / Other databases / Next steps

As today, for two containers. "Other databases" can mention mixing engines (EC on MySQL, points
on PostgreSQL) as a variation.

## Sample layout (`samples/getting-started`)

- `ec.sql`, `point.sql`
- `ec.properties`, `point.properties` (Schema Loader, one storage each)
- `frontend.properties` (multi-storage, phase 2)
- `import-ec.json`, `import-point.json`
- `src/main/java/.../EcApp.java`, `PointApp.java`

## Container checks (2026-11-11)

Two PostgreSQL 17 containers, Schema Loader 3.20.0, native driver/tools 3.20.0, the frontend built
from its tag v3.20.1. Script and configs: the session scratchpad's `ecpt/`.

- Import: `--coordinator` can't be combined with `--import` (DB-SCHEMA-LOADER-10009). Step 2 runs
  the Coordinator creation as a separate command, as today's guide does.
- The driver's `setup` and `check` work per database as today.
- The frontend starts on multi-storage over the two databases with imported tables only. It needs no
  `CREATE FRONTEND METADATA TABLES` and no other setup. Config: `storages=ec,point`,
  `namespace_mapping=ec:ec,point:point,coordinator:ec`, `default_storage=ec`.
- Collation: on multi-storage the frontend logs "Backend collation not checked", so `C` isn't required
  to start. Kept anyway (one `POSTGRES_INITDB_ARGS`): text ordering would be silently wrong
  otherwise.
- The global transaction over the frontend works as written: `SELECT … FOR UPDATE` by key on
  `wallet` and `point.point_account`, two `UPDATE`s, two `INSERT`s, `COMMIT`.
- Mixed load, 20 s, 2 threads per workload. All three invariants hold in every run: the wallet
  total = start + top-ups − paid money; the points total = start + the history's deltas; the orders'
  paid points = the history's spends.
  - One customer, tight loops: correct, but the global path starves (5 commits vs ~5,300
    conflicts; the local loops win the hot row).
  - 20 customers, 20 ms pacing: global 1,060 commits / 142 conflicts; local 1,182–1,359 / 24–64.
  - Kept as a check of the scenario, not as a reader-facing step (step 6 is the two-terminal version).
- Version trap: a frontend built against ScalarDB 4.0-SNAPSHOT writes a Coordinator column
  (`tx_write_set`) that Schema Loader 3.20 doesn't create, so every commit ends with
  DB-CORE-40003 (status unknown). The guide must pin the frontend to the same ScalarDB line as the
  Schema Loader.
- Recovery: that failed commit left one inserted `point_history` row prepared. The driver's
  aggregate read refused it ("roll the transaction back and retry"), and `check` reported it.
  Reading that row through the frontend rolled it back (lazy recovery). Worth one line in
  troubleshooting.

## Open questions

1. The frontend image `ghcr.io/scalar-labs/scalardb-postgres-frontend:3.20.1` is to be published
   soon (the user, 2026-11-11). The container path was checked with a local build of the v3.20.1
   tag: shared Docker network, mounted properties, `-p 127.0.0.1:…:5432`. The global transaction and
   the 20-customer mixed run passed, with the same three invariants. Recheck with the published image
   once it's out. Rechecked with the published image 2026-11-11: the global transaction and the
   20-customer mixed run passed (global 936 commits / 139 conflicts), with all three invariants.
2. Order ids: app-generated (UUID text), not `IDENTITY`.
3. Length: ~750 lines, accepted.
