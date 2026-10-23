---
status: complete
priority: p2
issue_id: "323"
tags: [passthrough, setup, catalog, docs, all-engines]
dependencies: []
---

# Passthrough, and views, triggers and cascades, reach ScalarDB tables without the protocol

## Problem Statement

With passthrough on, the driver sends a statement as written, whatever tables it names: on a
ScalarDB table it reads prepared records as committed and writes rows without their transaction
metadata, which breaks ScalarDB's transactions and the data (T5: Elle finds G0, G1c and
incompatible orders on every engine when one client does so beside ScalarDB). The design allows
passthrough on a ScalarDB table for DDL and maintenance with the table quiesced, a condition the
driver leaves to the application.

Other objects reach a ScalarDB table under another name, outside passthrough too: a user view of it
(design section 7), a trigger on another table that writes it, a foreign key with `ON DELETE` or
`ON UPDATE CASCADE` into it, a procedure or function that writes it, dynamic SQL. The driver sees
only the name the statement uses. Procedure calls and synonyms are already refused outside
passthrough.

## Findings

- Found while discussing T5's control run, 2026-10-23; the user decided: refuse such passthrough
  statements, P2.
- The parser cannot read about 6% of PostgreSQL's regression statements (todo 314), and the
  statements passthrough exists for (vendor DDL, procedural blocks, maintenance commands) often
  not: refusing every unparsed statement in passthrough would leave passthrough nearly useless.
- No check of a statement's text sees a procedure's body, dynamic SQL built from parts, a trigger
  or a cascade; the database's catalog shows views, triggers and foreign keys.

## Proposed Solutions

### Option 1 (agreed)
1. **Passthrough:** refuse (`0AN01`) a statement that names a ScalarDB table, its data, metadata or
   virtual table, or ScalarDB's own namespaces, found by its words (comments skipped, string
   literals included, so that a function body or dynamic SQL naming the table is refused too), so
   that an unparsed statement is checked as a parsed one is.
2. **Setup:** report views that depend on a ScalarDB table, triggers whose body names one, and
   foreign keys that cascade into one.
3. **README:** what not to create over a ScalarDB table, and what passthrough cannot check.

**Effort:** Medium. **Risk:** a statement refused for a word that names no ScalarDB table there (a
column or a literal of the same name): run it on a vendor connection.

## Acceptance Criteria

- [x] A passthrough statement naming a ScalarDB table, parsed or not, is refused; one that names
  none still runs.
- [x] The setup reports views, triggers and cascading foreign keys over ScalarDB tables.
- [x] README and design say what remains the application's.
- [x] T5's control run uses a plain vendor connection (2f35f66).

## Work Log

### 2026-10-23 - Filed

**By:** Claude Code

### 2026-10-23 - Done (Option 1)

**By:** Claude Code

- `SqlWords.find`: the words of a statement, comments blanked per engine (`--`, `/* */`, `#` on
  MySQL and MariaDB), string literals and quoted identifiers kept. `Router.decide` refuses a
  passthrough statement that contains the name of a ScalarDB table, its data, metadata or virtual
  table, ScalarDB's catalog schema or `coordinator` (`0AN01`). A failing catalog fails passthrough
  too: it cannot tell what is safe (RouterTest now runs on a database without ScalarDB).
- `Check.objects`: per engine, views, triggers and routines whose definitions name a ScalarDB table
  (on Oracle `ALL_DEPENDENCIES`, the definitions being LONG), triggers on one, and foreign keys on
  one whose delete or update rule changes its rows; the driver's read views and ScalarDB's virtual
  tables left out. The tools' `check` reports them (exit 1), `setup` warns of them.
- Tests: SqlWordsTest; RoutingIntegrationTest (PostgreSQL) refuses `VACUUM`, an `UPDATE`, a `DO`
  block with dynamic SQL and a write of `scalardb.metadata`, and of a plain table of the same name
  in another schema, and still runs one naming none; ObjectsCheckIntegrationTest on all five
  engines. Run beside the other suites, the check also found what they leave: a trigger on a
  ScalarDB table (`wb.trg_k`) and functions reading one (`sn_plain.fsum`, `tvf`).
- Full unit and integration suites on all five engines pass.

## Resources

- docs/design.md 4.1 (passthrough rows), 4.7, section 7; docs/plans/testing.md T5; todo 314.
