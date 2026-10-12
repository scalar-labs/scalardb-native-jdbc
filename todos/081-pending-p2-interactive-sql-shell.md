---
status: pending
priority: p2
issue_id: "081"
tags: [tooling, usability]
dependencies: []
---

# Interactive SQL shell

## Problem Statement

The driver is reachable only from JDBC code. Customers expect a psql-style REPL, and psql itself is unsafe on ScalarDB tables: it bypasses the driver, so reads see prepared records and writes leave no row metadata.

## Findings

- SQLLine, the usual JDBC shell, is unmaintained: last release 1.12.0 (2021-11), last push 2023-07.
- JLine 3 is maintained and covers line editing, history, completion, masked password input and Windows terminals; SQLLine is a thin layer over it.
- DBeaver and DataGrip work with a custom driver entry (our jar plus the vendor jar) but are GUIs.
- No generic shell can set `scalardbnative.passthrough`, which only `setClientInfo` accepts.

## Proposed Solutions

### Option 1: Own shell on JLine 3 and picocli

**Approach:** New `shell/` module depending only on JLine and picocli; not `tools/`, which carries `scalardb`. Statements end at `;` across lines; history in `~/.snsql_history`; aligned result tables; meta-commands `\dt`, `\d table` (from `DatabaseMetaData`, ScalarDB tables marked), `\c url`, `\autocommit`, `\commit`, `\rollback`, `\isolation`, `\passthrough on|off`; table and column completion. Vendor driver on the classpath; a `--driver-jar` option needs a shim driver because `DriverManager` rejects drivers from a foreign class loader.

**Pros:** psql-like UX; driver-aware features no generic shell has.

**Cons:** A second artifact to maintain.

**Effort:** Medium (300-500 lines plus tests)

**Risk:** Low

### Option 2: Fork SQLLine (BSD-3)

**Approach:** Fork and retarget.

**Pros:** Feature-complete at once.

**Cons:** Inherits about 15k lines of unmaintained, every-database code.

**Effort:** Medium

**Risk:** Medium

### Option 3: Document JShell and GUI clients only

**Approach:** A JShell startup script with a `q(sql)` helper, plus DBeaver/DataGrip setup notes.

**Pros:** No code to maintain.

**Cons:** Not what psql users expect.

**Effort:** Small

**Risk:** Low

## Recommended Action

Option 1, planned in `docs/plans/shell.md`. Milestone 1 done 2026-10-11 (the `shell` module, `snsql`); milestone 2, the catalog commands, `\passthrough`, completion and the other engines, remains.

## Acceptance Criteria

- [x] `snsql -U user jdbc:scalardb-native:postgresql://...` connects, prompting for the password.
- [ ] Multi-line statements, history, aligned output, the meta-commands above (all but the catalog commands, `\passthrough` and completion, which are milestone 2).
- [ ] Integration test drives the shell against each engine configuration.

## Work Log

### 2026-10-11 - Raised

**By:** Claude Code

- Customers are expected to ask for a psql-like tool soon.

### 2026-10-11 - Milestone 1

**By:** Claude Code

- `shell` module: `Main`, `Repl`, `StatementSplitter`, `Printer`; unit tests for the splitter and the transaction mapping; `ShellIntegrationTest` on PostgreSQL (plain table: the driver does not write ScalarDB tables yet).
- Checked by hand on PostgreSQL through a pseudo-terminal: password prompt, prompts, continuation, Ctrl-C cancel, `\conninfo`, history; the zip on JDK 11, 17, 21 and 25.

## Resources

- `docs/plans/shell.md`
