---
status: pending
priority: p2
issue_id: "249"
tags: [code-review, mysql, mariadb, isolation, passthrough]
dependencies: []
---

# Passthrough level follow misreads SETs: comment-prefixed SET ignored, REPEATABLE in any SET drops SERIALIZABLE, one-shot SET TRANSACTION untracked

## Problem Statement

`Router.ran` (~1703-1725) decides from the text whether a passthrough statement changed the level.
On MySQL and MariaDB, since 45afd86 (todo 244), it follows only text whose `strip()` starts with
`set`, and it keeps SERIALIZABLE when the vendor reports REPEATABLE READ unless the text contains
`repeatable`. Three cases go wrong:

1. `/* c */ SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED` (or `SELECT 1; SET SESSION ...`
   with allowMultiQueries) changes the session but is not followed: the driver stays at SERIALIZABLE
   or REPEATABLE READ while the vendor runs READ COMMITTED, the drift todo 222 fixed. Before 45afd86
   any text containing `isolation` was followed, so this is a regression.
2. At SERIALIZABLE (vendor at REPEATABLE READ), `SET GLOBAL TRANSACTION ISOLATION LEVEL REPEATABLE
   READ` or the one-shot `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ` leaves the session level
   at REPEATABLE READ; `getTransactionIsolation()` reads 4, the text contains `repeatable`, so the
   driver sets `isolation = 4` and silently drops the connection from SERIALIZABLE to SNAPSHOT for
   good.
3. The one-shot `SET TRANSACTION ISOLATION LEVEL READ COMMITTED` does not change the session level
   (`getTransactionIsolation()` still 4), so nothing is followed, but the vendor runs the next
   transaction that touches InnoDB at READ COMMITTED; statements like `SELECT 1` in between do not
   consume it. The driver's next transaction then runs its SNAPSHOT/SERIALIZABLE logic on a READ
   COMMITTED engine transaction.

## Findings

- JDBC probe (scratchpad `IsoProbe`) on both MySQL 8.4 and MariaDB 11.4 drivers: after
  `/* c */ SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED`, `getTransactionIsolation()`
  = 2; `text.strip().startsWith("set")` is false for it, so `Router.ran` skips the follow.
- After the one-shot `SET TRANSACTION ISOLATION LEVEL READ COMMITTED`, `getTransactionIsolation()`
  = 4 on both drivers.
- Raw sessions (scratchpad `oneshot.sh`) on both engines: after the one-shot SET, `SET
  autocommit=0` and a transaction reading `t` twice across a concurrent committed update sees the
  update (READ COMMITTED), also when `SELECT 1;` ran in between; when `SELECT a FROM t LIMIT 1;`
  ran in between, or `SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;`, it does not (the
  one-shot was consumed or cleared).
- Case 2 by the code: level 4, isolation 8, `text.contains("repeatable")` true, so the `else if`
  assigns `isolation = level`.

## Proposed Solutions

### Option 1: compare with the level the driver set on the vendor, not the text
Keep the vendor level the driver last set (REPEATABLE READ for SERIALIZABLE on MySQL/MariaDB). After
any passthrough statement, read `getTransactionIsolation()`; follow only when it differs from that
level. Then, when the text mentions `isolation` at all, re-send `SET SESSION TRANSACTION ISOLATION
LEVEL <vendor level>` (which clears a pending one-shot, probed) so the next transaction runs at the
level the driver believes. No text heuristics left but the cheap prefilter.

### Option 2: keep the heuristics, patch the cases
Strip leading comments before `startsWith("set")`, ignore `global`, and clear one-shots as above.
More fragile.

## Acceptance Criteria

- [ ] A comment-prefixed passthrough `SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED` is
      followed
- [ ] Passthrough `SET GLOBAL ... REPEATABLE READ` and one-shot `SET TRANSACTION ... REPEATABLE
      READ` keep the driver at SERIALIZABLE
- [ ] After a passthrough one-shot `SET TRANSACTION ISOLATION LEVEL READ COMMITTED`, the next driver
      transaction runs at the vendor level the driver expects

## Work Log

- 2026-10-18: filed by the focused review of 45afd86 and e24a850 (probes on MySQL 8.4 and
  MariaDB 11.4).
