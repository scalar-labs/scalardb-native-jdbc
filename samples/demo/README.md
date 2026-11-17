# A 15-minute demo of ScalarDB Native JDBC

A run sheet for showing the driver to developers, with the story of
[Getting started](../../docs/getting-started.md): an EC system with a prepaid wallet and a separate
point system, integrated with ScalarDB without merging them. Everything runs locally: two
PostgreSQL containers and ScalarDB's PostgreSQL frontend, on one tmux screen.

## Before the session

You need Docker, tmux, `psql` and Java 17 or later.

```sh
cd samples/demo
./demo.sh up
./demo.sh tmux
```

`up` downloads ScalarDB's Schema Loader once (`scalardb-schema-loader-3.20.1.jar`, here, or set
`SCHEMA_LOADER` to a copy you have), builds `snsql` and `scalardb-native-tools` from this repository,
starts the EC database (port 5432) and the point database (5433), and creates the two systems'
tables with plain DDL and ScalarDB's Coordinator table. Act 1 does the rest: the import of the shared tables into ScalarDB,
the driver's setup, and the frontend (port 15432), which reads ScalarDB's tables when it starts. It
uses the files in `samples/getting-started`. Customer 1 starts with 10,000 in the wallet and 500
points.

`tmux` opens the demo screen, sized to the terminal, so open it in the window you will present:

| | EC (left) | | Point system (right) |
| --- | --- | --- | --- |
| Top | **EC · local**: `psql` → EC database, then a shell | **EC · global**: `psql` → frontend | **Point · local**: `psql` → point database, then a shell |
| Bottom | **EC database**: wallet and orders, every 2 s | | **Point database**: points and history, every 2 s |

The top panes wait for SQL. Click a pane to type in it. The local panes open in `psql`, as the apps
connect today; quitting `psql` leaves a shell (`ec$`, `point$`) for the import and `snsql`. **EC ·
global** waits until both databases are imported, then starts the frontend and connects. The bottom
panes read with `psql` before a database's import and through the driver after it. Between
rehearsals, `./demo.sh reset` undoes the import, puts the data back and restarts the top panes (the
screen can stay open). Ctrl-C in a pane that is waiting, or `exit` in a shell, closes the pane;
`./demo.sh tmux` opens the screen again. After the session, `./demo.sh down` removes the containers and the tmux session.

## The run sheet

| Time | Part | What to do |
| --- | --- | --- |
| 0:00 | The ask | Slides: two systems, two teams, two databases; the business wants "pay with points" |
| 2:00 | How it works | Slides: the driver for local transactions, the frontend for global ones |
| 5:00 | Act 1: adopt | Import the tables, change the URL; the apps' SQL, unchanged, through the driver |
| 8:00 | Act 2: pay with points | One transaction across both databases |
| 10:00 | Act 3: the two meet | A local and a global transaction on the same row |
| 12:00 | Code | Slide: local and global are the same JDBC code |
| 13:00 | Performance, limits | Slide |
| 14:00 | Questions | |

### Act 1: adopt, no change but the URL

Today each app connects to its own database directly. In **EC · local**, still `psql`, a top-up
(`topup_history` is an ordinary table, in the same local transaction):

```sql
BEGIN;
UPDATE ec.wallet SET balance = balance + 1000 WHERE customer_id = 1;
INSERT INTO ec.topup_history (customer_id, amount) VALUES (1, 1000);
COMMIT;
```

In **Point · local**, a check-in earns points:

```sql
BEGIN;
UPDATE point.point_account SET points = points + 50 WHERE customer_id = 1;
INSERT INTO point.point_history VALUES ('h0', 1, 50, 'checkin');
COMMIT;
```

The bottom panes show 11,000 and 550. Now each team adopts. Type `\q` in both local panes; each
leaves a shell. Show `samples/getting-started/import-ec.json` for a moment: the wallet and the
orders, not the top-up history. In **EC · local**, import the EC database's shared tables into
ScalarDB and set them up for the driver:

```sh
./demo.sh import ec
```

It prints and runs the two commands, the Schema Loader's `--import` and `scalardb-native-tools
setup`, in about 5 seconds. The import kept the tables and their rows: the bottom pane still shows
11,000. Then reconnect the app through the driver: press ↑ for the prepared command and Enter.

```sh
snsql -U postgres jdbc:scalardb-native:postgresql://localhost:5432/postgres
```

Point out the two URLs on screen, `psql -h localhost -p 5432` before and
`jdbc:scalardb-native:postgresql://...` now: the URL is the change. In **Point · local**, the point
team does the same with `./demo.sh import point` and ↑, Enter. Once both are imported, **EC ·
global** starts the frontend and connects.

Run the apps' SQL again, now through the driver. In **EC · local**:

```sql
BEGIN;
UPDATE ec.wallet SET balance = balance + 2000 WHERE customer_id = 1;
INSERT INTO ec.topup_history (customer_id, amount) VALUES (1, 2000);
COMMIT;
```

In **Point · local**:

```sql
BEGIN;
UPDATE point.point_account SET points = points + 50 WHERE customer_id = 1;
INSERT INTO point.point_history VALUES ('h1', 1, 50, 'checkin');
COMMIT;
```

The bottom panes show 13,000 and 600. Point out: the import added ScalarDB's metadata columns to the
tables, but `SELECT *` in the bottom panes returns the tables' own columns, and nothing about the SQL
is ScalarDB-specific.

### Act 2: pay with points

In **EC · global**, buy a 1,500 book with 400 points and 1,100 from the wallet, one transaction
across both databases. The connection's database is `ec`; the point tables are named with their
namespace:

```sql
BEGIN;
INSERT INTO ec.orders VALUES ('o1', 1, 'book', 1500, 1100, 400);
UPDATE point.point_account SET points = points - 400 WHERE customer_id = 1;
INSERT INTO point.point_history VALUES ('h2', 1, -400, 'order o1');
UPDATE ec.wallet SET balance = balance - 1100 WHERE customer_id = 1;
COMMIT;
```

Both bottom panes change together: the wallet 11,900 with the order, the points 200 with the
history. To show atomicity, run it again with `ROLLBACK` in place of `COMMIT` (and new ids): neither
side moves.

### Act 3: the two meet

In **EC · global**, start another purchase, a global transaction that reads the wallet and the
points (11,900 and 200 after act 2):

```sql
BEGIN;
SELECT balance FROM ec.wallet WHERE customer_id = 1;
SELECT points FROM point.point_account WHERE customer_id = 1;
```

In **Point · local**, the point app earns 10 points meanwhile; the point pane shows 210:

```sql
UPDATE point.point_account SET points = points + 10 WHERE customer_id = 1;
```

Back in **EC · global**, finish the purchase, a 300 cup paid with 100 points and 200 from the wallet:

```sql
INSERT INTO ec.orders VALUES ('o3', 1, 'cup', 300, 200, 100);
UPDATE point.point_account SET points = points - 100 WHERE customer_id = 1;
INSERT INTO point.point_history VALUES ('h4', 1, -100, 'order o3');
UPDATE ec.wallet SET balance = balance - 200 WHERE customer_id = 1;
COMMIT;
```

The commit fails with `DB-CORE-20013`, SQLState `40001`: it would have written 100 points over the 10
just earned. The whole purchase is refused, on both sides: the EC pane shows no cup and still 11,900,
the point pane still 210. The app retries; run the same block again from `BEGIN`, and the purchase
reads 210 and commits: 11,700 and 110. It works the other way round too: a local transaction that
conflicts with a global one fails with `40001` through the driver.

## Notes

- A bottom pane that reads while a global transaction commits can meet a row in flight; it keeps
  its last output and shows the next refresh.
- `snsql` here is built from this repository, so its banner shows the repository's version.
- The frontend runs without authentication, reachable from this machine only
  (`127.0.0.1:15432`); in production it runs inside ScalarDB Cluster's nodes.
