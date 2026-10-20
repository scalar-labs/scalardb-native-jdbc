---
status: pending
priority: p3
issue_id: "052"
tags: [code-review, testing, build]
dependencies: []
---

# The five-engine harness starts containers serially and lacks what the interleaving matrix will need

## Problem Statement

`Engines` is the right seam for the five-engine matrix the design promises, but as committed it starts the containers one at a time under a lock, cannot point at an external database, creates the SQL Server database without the snapshot settings the driver will require at connect, fixes ScalarDB's isolation per engine rather than per test, runs Oracle only as `SYSTEM`, and CI runs all five containers serially in a single `check` job. Todo 021 covers Docker inside `check` and per-class containers; these are the gaps beyond it.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java:134-145`: `started()` creates and starts one container at a time inside `synchronized (STARTED)`. With `all`, Oracle (about 60-90 s) plus emulated SQL Server (about 60 s) plus three at 5-10 s run serially: roughly 2.5-3 minutes before the first assertion, versus about 1.5 minutes if started together (performance-oracle). Testcontainers 2.0.5 has `org.testcontainers.lifecycle.Startables.deepStart`; `withReuse(true)` with `testcontainers.reuse.enable=true` would keep containers across local runs.
- `Engines.java:118-128`: the only property read is `nativejdbc.engines`; no `-Dnativejdbc.<engine>.url/username/password` override, which `docs/design.md:333` promises ("a system property to point at an external database instead") and a nightly needs. `Started` (`:85-95`) is the seam.
- `Engines.java:55-64`: `CREATE DATABASE appdb` on SQL Server without `ALTER DATABASE appdb SET READ_COMMITTED_SNAPSHOT ON` / `ALLOW_SNAPSHOT_ISOLATION ON`; the driver's connect-time check (design section 1, SQL Server requires RCSI) will refuse the harness's own database.
- `Engines.java:113-115, 166`: ScalarDB's isolation is fixed per engine (`REPEATABLE_READ`, `SERIALIZABLE` on Oracle); the matrix needs it per test (`SERIALIZABLE` for the SERIALIZABLE row of design section 5).
- `Engines.java:72-75`: Oracle runs as `system`, so privilege-dependent resolution through `all_objects` is never exercised as an application user; todo 033 needs a restricted user.
- `.github/workflows/ci.yml:35-38` runs plain `check`, so all five containers start serially in one job; `docs/design.md:342-345` promises an engine matrix job.

## Proposed Solutions

### Option 1: Parallel start, external override, RCSI, per-test isolation, app user, CI matrix

**Approach:** Create the containers for `selected()` up front and `Startables.deepStart(containers).join()` once, then derive `url`/`username` per engine as now. Honour `-Dnativejdbc.<engine>.url/username/password` in `started()` and skip the container when set. Run the two `ALTER DATABASE` statements in the SQL Server `url()`. Give `admin()` an isolation parameter. Add a non-DBA application user on Oracle (`GRANT CREATE SESSION` plus object grants) for resolution and privilege cases. In `ci.yml`, a `strategy.matrix.engine` over the five with `./gradlew integrationTest -Dnativejdbc.engines=${{ matrix.engine }}` and the base job on `check -x integrationTest`.

**Pros:**
- Halves local start time; the harness matches what the driver will require; CI time is bounded by the slowest engine, not the sum.

**Cons:**
- Six changes across the harness and CI.

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Parallel start and RCSI only

**Approach:** `Startables.deepStart` and the two `ALTER DATABASE` statements now; the rest when the interleaving tests arrive.

**Pros:**
- The two items that bite the next step immediately.

**Cons:**
- The external-database promise and the CI matrix stay open.

**Effort:** 1 hour

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java:55-64, 72-75, 113-145, 166`
- `.github/workflows/ci.yml:35-38`
- `driver/build.gradle.kts:59-65` - the engine selector forwarding (already configuration-cache safe)
- `docs/design.md:333, 342-345` - the promises to keep

**Related components:**
- Todo 021 (Docker in `check`, per-class containers), 033 (restricted-privilege Oracle case), 032 (a `lower_case_table_names=1` MySQL container).

**Database changes:** None (test fixtures only).

## Resources

- Testcontainers `Startables.deepStart`, container reuse.
- Found by: architecture-strategist (P3), performance-oracle (P3), agent-native-reviewer (P3).

## Acceptance Criteria

- [x] With `all`, the five containers start concurrently; the first assertion runs within about 1.5 minutes locally.
- [x] `-Dnativejdbc.postgresql.url=...` (and username/password) runs the PostgreSQL leg against that database without a container.
- [x] The SQL Server fixture database has `READ_COMMITTED_SNAPSHOT` and `ALLOW_SNAPSHOT_ISOLATION` on.
- [x] `admin(isolation)` lets a test choose ScalarDB's isolation (as `scalarDb(Properties)` with `scalar.db.jdbc.isolation_level`, which it already honoured).
- [ ] An Oracle application user without DBA rights exists for the resolution and privilege cases.
- [x] CI runs one job per engine plus a `check -Dnativejdbc.engines=none` job.

## Work Log

### 2026-10-20 - Done for milestone T0 of docs/plans/testing.md, but the Oracle application user

**By:** Claude Code

**Actions:**
- java-conventions: one `integrationTest<Engine>` task per engine configuration, each in its own JVM and
  container, `check` depending on those `-Dnativejdbc.engines` selects (`none` for none); a shared
  build service caps them at three at a time (`-Pnativejdbc.parallelEngines`). `integrationTest` stays
  the one-JVM task for `--tests` runs. Every integration task gets the `-Dnativejdbc.*` options.
- `Engines`: the selected engines start together (`Startables.deepStart`); Oracle's startup timeout
  raised to 10 minutes, after it timed out with six starting at once; `-Dnativejdbc.<engine>.url`,
  `.username`, `.password` use an existing database instead of a container.
- `ci.yml`: `check -Dnativejdbc.engines=none`, and a matrix job per engine.
- Measured locally, the driver's suite, three tasks at a time: MySQL, MySQL lower case, MariaDB 5.2-5.6
  min each; Oracle 5.3, PostgreSQL 4.1, SQL Server 7.0; about 13 minutes for the six, against about 45
  in one task. Peak container memory with Oracle, PostgreSQL and SQL Server together 4.3 GB of
  Docker's 7.75 GB (Oracle about 2 GB, MySQL about 0.6 GB), so three stays the default.
- Left open: the Oracle application user, until a test needs one (todo 033, its first user, is done
  without it). Gradle starts the per-engine tasks in name order, so the slow engines go last; starting
  them first would save a few minutes and needs no more than ordering.


### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- performance-oracle measured serial container start and named `Startables.deepStart`; architecture-strategist listed the external override, RCSI, isolation and app-user gaps against the design; agent-native-reviewer checked `ci.yml` against the promised matrix.

**Learnings:**
- The harness must satisfy the driver's own connect-time checks before the proxies land, or the first proxy test fails on the fixture, not the code.

## Notes

- The `lower_case_table_names=1` MySQL container belongs to 032's acceptance criteria but will share this harness.
