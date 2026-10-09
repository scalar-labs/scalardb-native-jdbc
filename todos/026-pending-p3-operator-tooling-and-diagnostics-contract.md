---
status: pending
priority: p3
issue_id: "026"
tags: [code-review, design, usability]
dependencies: ["016"]
---

# Setup tool, sweeper, catalog refresh and logging are specified as rituals, not drivable contracts

## Problem Statement

The design names a setup command, a sweeper and SLF4J logging, but says nothing an automated caller or an
operator script can rely on: no arguments, exit codes, idempotency, dry-run or machine-readable output for
the tools; no way to refresh the catalog through the JDBC surface after running setup on a live system; and
no logger category or event list. Each of these will be built in a later step, so the cost of fixing them
now is a paragraph of design, not code.

## Findings

- Setup command (`docs/design.md` section 3 and the `tools` module note): unspecified are the arguments (URL,
  metadata schema, one table / a list / every ScalarDB table from the catalog), idempotency (re-runnable,
  `CREATE OR REPLACE` or exists-check), a `--check` mode that runs the same verification the driver runs at
  connect so an operator does not need an application connection to learn what is missing, `--dry-run`
  printing DDL for change control, the backfill's termination condition and sentinel `tx_id`, that the tool
  sets `QUOTED_IDENTIFIER ON` itself on SQL Server rather than documenting it, exit codes and a machine-readable
  summary, that it never prompts, and the channel for the covering-index warning (today implied to be a log
  line; it belongs in `--check` output).
- Sweeper (`docs/design.md` 4.4 and section 6): unspecified are its ScalarDB configuration surface
  (`database.properties`, a different surface from the driver URL), one pass vs loop and its interval, table
  discovery (reuse `Catalog`, so `tools` depends on `driver`, or duplicate the lookup), whether concurrent
  sweepers are safe (`recoverRecord`'s lifetime guard makes them so; say it), per-row outcomes (recovered /
  not yet expired / threw), exit code, and a `--dry-run` listing expired in-flight rows, which doubles as the
  "what is stuck" diagnostic.
- Catalog refresh: `Catalog.invalidate()` is public
  (`driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:105`) but the `Catalog` will live inside
  the driver, and refresh is specified only as miss, "not found" error and TTL, none of which an operator can
  trigger after importing a table.
- Logging: `NativeJdbcDriver.getParentLogger` throws "The driver logs through SLF4J"
  (`driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:103-105`), but no `org.slf4j` import
  exists in main (only the dependency at `driver/build.gradle.kts:29`); the design names no logger category and
  no events.

## Proposed Solutions

### Option 1: One CLI paragraph and one logging paragraph in the design

**Approach:** In section 3 fix the CLI contract: subcommands `setup`, `check`, `backfill`, `sweep`; `--table`
or all; `--dry-run`; `sweep --once | --interval <ms>`; exit 0 / 1 (something missing or a `recoverRecord`
threw) / 2 (error); a line-per-table or JSON summary; never prompts; sets `QUOTED_IDENTIFIER ON` itself.
State that `tools` depends on `driver` for the catalog lookup and that concurrent sweepers are safe. Add a
`setClientInfo("scalardbnative.refreshCatalog", ...)` key next to `passthrough` for refresh. Name the logger
(`com.scalar.db.nativejdbc`), the events (rewritten SQL, refusals, catalog refreshes, in-flight waits) and
their levels.

**Pros:**
- Cheap now; expensive to retrofit once scripts depend on ad-hoc output.
- Gives the agent-native and operator paths the same contract.

**Cons:**
- Design-only; nothing runs until the `tools` module exists.

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Expose refresh and the rewritten statement programmatically as well

**Approach:** Option 1 plus `Connection.unwrap(Catalog.class)` for refresh and a `Statement.unwrap` accessor or
a `SQLWarning` carrying the rewritten statement, so an automated caller can inspect what ran without DEBUG
logs.

**Pros:**
- The one thing a caller will want when a result surprises it is reachable in code.

**Cons:**
- Two more public surfaces to keep stable.

**Effort:** Half a day when the proxies exist

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `docs/design.md` sections 1 (properties), 3, 4.4 and 6
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:103-105`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:105`
- future `tools` module

**Related components:**
- 016 (refusal error contract) defines the SQLSTATE for "setup not run on table T" that `check` must mirror.
- 017 names the driver property list that `refreshCatalog` joins.

**Database changes:** No

## Resources

- `docs/design.md` sections 3, 4.4, 6 and the `tools` module note.
- Found by: agent-native-reviewer (P3-5, P3-6, P3-7, P3-8).

## Acceptance Criteria

- [ ] Section 3 states the CLI subcommands, arguments, dry-run, exit codes and summary format.
- [ ] Section 4.4 or 6 states the sweeper's configuration surface, loop mode, discovery and concurrency safety.
- [ ] The design lists a way to refresh the catalog from a live connection.
- [ ] The logger category, events and levels are written down.
- [ ] `getParentLogger` behaviour matches what the design says about logging.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the agent-native-reviewer report (P3-5 through P3-8) of the seven-agent review of
  main @ 06c137f.

**Learnings:**
- Everything implemented so far is driveable without a human; the gaps are in planned pieces only.

## Notes

- Design-only item; schedule it with the first `tools` commit so the contract and the code land together.
