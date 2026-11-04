---
status: pending
priority: p2
issue_id: "327"
tags: [ci, docs, compatibility, vendor-driver]
dependencies: []
---

# The vendor driver versions the driver supports are neither tested nor documented

## Problem Statement

The application supplies its own vendor JDBC driver, and the driver uses whichever one
`DriverManager` returns for the stripped URL, at whatever version (`NativeJdbcDriver.vendorDriver`).
Nothing checks that version, and the integration tests run each engine against one version only, the
one in `gradle/libs.versions.toml`. So the user docs cannot say which versions work: an older
driver that behaves differently fails later as a wrong result or an odd error, not at connect.

## Findings

- The versions the tests run today: pgjdbc 42.7.14, Connector/J 26.7.0, MariaDB Connector/J 3.5.10,
  mssql-jdbc 13.6.0.jre11, ojdbc11 23.26.3.0.0 (`gradle/libs.versions.toml`).
- The only version checks in the driver are of the database server (`getDatabaseMajorVersion` in
  `Router` and `Setup`), never of the vendor driver.
- `.github/workflows/ci.yml` runs one job per engine, at those versions.

## Proposed Solutions

### Option 1: test a range in CI, then document it
1. Let each vendor driver version be overridden from the command line, e.g.
   `-Pnativejdbc.postgresql.driverVersion=42.5.0`, for the integration test tasks.
2. Add CI jobs that run each engine at the oldest version to be claimed, beside the current one.
3. Write the tested range per engine into the README's compatibility table and the user docs.

**Effort:** Medium.

## Recommended Action

Option 1. Decided 2026-10-23: no warning or refusal at connect for a vendor driver version for now;
the supported versions are documented only once CI verifies them.

## Acceptance Criteria

- [ ] Each engine's integration tests can run at a vendor driver version given on the command line.
- [ ] CI runs each engine at the oldest supported vendor driver version and at the current one.
- [ ] The supported range per engine is documented.

## Work Log

### 2026-10-23 - Raised while planning the user docs

**By:** Claude Code
- Getting started asks users to add their database's own JDBC driver; whether to bundle the vendor
  drivers or publish per-engine starter artifacts was weighed and dropped, so the supported versions
  need stating instead.

### 2026-10-29 - The tested versions documented for 3.20.0

**By:** Claude Code
- README, Compatibility: a table of the server and JDBC driver versions CI tests per engine, said to
  be the tested ones and other versions unverified; the zips' bundled drivers named. The third
  acceptance criterion holds for exact versions only: the override and the oldest-version CI jobs
  (criteria 1 and 2) are open, and need the oldest version to claim per engine decided.

### 2026-10-30 - Driver ranges tested; the server-version matrix planned for after 3.20.0

**By:** Claude Code
- Done: `-Pnativejdbc.<engine>.driverVersion` and `-Pnativejdbc.excludeTests` in driver/build.gradle.kts,
  `.github/workflows/vendor-drivers.yml` (by hand and weekly), and the README's ranges from run
  40906274113 (52b510d). Run 40975593718 tries ojdbc11 23.4 to 23.8 to widen the Oracle 23 range.
- Next, after the release: the database server versions, on two axes rather than the full product:
  1. Server versions with CI's current driver: every major the vendor still supports past the
     release, checked against the vendors' end-of-life dates when the matrix is built. PostgreSQL 15
     to 18 (decided 2026-10-30: 14 reaches its end of life in November 2026 and is left out; 18 is
     the newest, no postgres:19 image yet; CI's default, 16, may move to 18 once it passes). The
     others to check the same way: MySQL 8.0, 8.4 and 9.x, MariaDB 10.6, 10.11 and 11.4, SQL Server
     2019 and 2022, Oracle 21c XE beside Free 23 (19c has no free container image).
  2. Driver versions with one server, as the workflow does now.
  3. The corners: the oldest driver line against the oldest server, per engine.
  About 25 to 30 more jobs. Needs an image override per engine in `testing/Engines.java`
  (`-Pnativejdbc.<engine>.image`), shared with other sessions' tests. The driver branches on the
  server version (PostgreSQL 15 in Setup, 16 in Router), so the server range is a real claim. The
  README's Server column then becomes a range.

### 2026-10-30 - Oracle 23.4 to 23.8 in the range; Oracle 19c and a flaky test next

**By:** Claude Code
- Run 40975593718: ojdbc11 23.4, 23.5, 23.7 and 23.8 pass; 23.6 and 23.26.0 failed only
  `WriteIntegrationTest.reviewFindings` with ORA-12516 (the Oracle Free container out of
  connections, seen with CI's driver too) and were rerun. README (5250405): ojdbc11 21.1 to 21.23
  and 23.4 to 23.26; the server named Oracle AI Database 26ai Free (23.26), as the container reports.
- After the release:
  1. Oracle 19c, the other long-term release (23ai is now 26ai, release update 23.26; 21c was an
     innovation release, skipped): `ghcr.io/scalar-labs/oracle/db-prebuilt:19` (19c SE2 19.3.0,
     built 2024-07-16). Needs an image override in `testing/Engines.java` with its own service name,
     credentials and startup wait, read access for this repository on the package, and a GHCR login
     in the workflow. Expect 19c fallbacks in the Oracle statement forms (no IF EXISTS, no BOOLEAN).
     A current release update (RDS 19.32 in bench-env) can confirm later.
  2. Oracle 26ai Enterprise Edition: one run of the suite on AWS beside 19c, since Free and EE share
     the code base; CI stays on Free.
  3. The flaky test: `reviewFindings` on Oracle exhausts the Free container's connections now and
     then (ORA-12516); fewer connections at once in the test, or a higher process limit in the
     test container, so the weekly run stays a signal.

## Resources

- `NativeJdbcDriver.vendorDriver`, `gradle/libs.versions.toml`, `.github/workflows/ci.yml`.

### 2026-10-31 - The 19c bulk INSERT design reviewed (design 4.4, oracle-19c dd0cf56)

**By:** Claude Code (the design session, from `git show`)
- feaff40 reviewed against design 4.4 and Writes.java on main c4a7fcf: the `NOT EXISTS` metadata
  insert below 23 is right against the block. The takeover, the stamp condition and the abort count
  work from the list of entries counted 0 alone, and the two forms differ only in that list: a row
  there but not visible fails the insert with ORA-00001, SQLState 23000, `undoable`, the block undone
  whole and the entries one by one through the single INSERT block, the same `40001` at one round
  trip more; at SNAPSHOT the `NOT EXISTS` form meets ORA-00001 first and the single form's takeover
  ORA-08177. Nothing else reads `SQL%BULK_ROWCOUNT` after a hinted FORALL (the other hinted inserts
  read `SQL%ROWCOUNT`, one row's or the total; the other `BULK_ROWCOUNT` reads follow plain DML).
  The cost claim holds: one unique scan of `T_meta`'s key per entry, 2 to 3 gets, about a third
  more gets on a 10-entry block than the hinted text, which is why 23 keeps the hint.
- Six wording edits asked (the SNAPSHOT sentence, the repeated key, the cost's magnitude, where the
  version is read and the text cache keyed by the form, 21c, the acceptance tests named) and made
  by the 19c session as dd0cf56 on `oracle-19c`, 38 fences. Mergeable. Open for the build:
  `form4InsertAsOneStatement`'s 2 round trips on 19c against 1 on 23, `onMetadataTable`'s
  `all_constraints` lookup to rule out first.

### 2026-10-31 - c7's build of the 19c bulk INSERT form reviewed (oracle-19c-bulk-insert ae274d0)

**By:** Claude Code (the design session, from `git show`)
- Mergeable on the 19c CI run coming back green (`MoreWritesIntegrationTest.batches`, the new OLTP
  test). `Bulk.sql(types, major)` takes the `NOT EXISTS` text for the INSERT below 23 under its own
  cache key and the hinted text byte for byte otherwise (WritesTest); the version read once per
  connection in `ConnectionHandler`, 0 unread, carried through `applyOracleEntries` to
  `applyOracleBulk`, the one site that needed it; probe 9c asserts `1,1,1,/2` below 23; the OLTP
  test runs the eight tombstone cases (two levels, states 3 and 1, held then committed or committed
  after the read) on both forms through `Proxies.oracleMajorForTests`, SQLState and rows alike.
- Decided: (a) an abort's counts before it differ (`{[1]}` from the entries one by one against the
  hinted block's `{[]}`): the design's own table gives both, so one clause in the 19c paragraph, no
  change to the build; (b) version 0 takes the `NOT EXISTS` form: kept, correctness first on a path
  that occurs only on a broken connection. Notes: land feaff40 + dd0cf56 (oracle-19c) with or before
  the build; `oracleMajorForTests` is JVM-wide, sound while the suite runs sequentially; form4's 2
  round trips on 19c need the cause (a 10046 trace of the test's session) before the 19c run can
  pass that assertion.
- 2026-11-01 (33's 19c CI result for the bulk INSERT build, oracle-19c 18e05ee carrying ae274d0/e38d6c9,
  run 41206828260, relayed to 89): oracle-23 green; oracle-19c 4 of 167 failed. The merge condition
  held: `MoreWritesIntegrationTest.batches` passed on 19c, the bulk block tests too, the tombstone
  taken over at both levels (metadata 30/3/3 and 40/3/3). The four: (1) the new OLTP test expects the
  hinted form where 19c runs NOT EXISTS, a test bug, c7 makes the expectation version-conditional
  against outcomes measured below 23; (2) `forallAndReturning`'s hinted probe records 1/1/1/1 on 19c,
  c7 makes it version-conditional as 9c is; (3) form4 counts [2, 2] round trips on both passes, so not
  a cold lookup, 33 takes a 10046 trace; (4) ORA-08177 in ScalarDB's `importTable`, the known flaky:
  ScalarDB's connection at Oracle SERIALIZABLE (the fixture's `REPEATABLE_READ`), the same level todo
  366 traces its failures to; on an inline table the fixture can run `READ_COMMITTED` (366's option 1,
  the user's call), which would remove it there. Rerun pending from 33.
- 2026-11-01 (33's 19c rerun, oracle-19c 93f544a = ae274d0 + e38d6c9 + c7's 20dc64b, run
  41280820558): 19c 167 tests, 1 failed, `form4InsertAsOneStatement` at [2, 2] round trips on both
  passes; the todo 327 test and `forallAndReturning` pass with the exact 19c figures, batches
  passes. 23: 2 failed, `WriteIntegrationTest.reviewFindings` and `ObjectsCheckIntegrationTest`,
  the known ORA-12516 connect-exhaustion flaky, untouched by the branch. 89's verdict: mergeable by
  the condition; 20dc64b read from `git show` (test-only: below 23 the NOT EXISTS pass alone, held
  to the hinted form's outcomes as measured on 23 with the abort counts left out as decided;
  forallAndReturning "ok 1/1/1/1" below 23 as probe 9c). Before the landing: attribute form4's
  second round trip by a 19c run of main at the merge base (the branch changes only Proxies and
  Writes, both on the INSERT path): main at 2 as well = 19c's baseline, a version-conditional
  expectation once the 10046 trace names the cause; main at 1 = the build's, a fix before landing.
- 2026-11-01 (form4 attributed): two 19c runs without the build, 41182797634 (oracle-19c 04b2c1c)
  and 41188432106 (5e25dac), driver code main at 7ed2254, failed `form4InsertAsOneStatement` at 2
  round trips as well; between 7ed2254 and the build's base 0e5b5f9 only c4a7fcf and 36dfb48 touch
  the driver and no hunk of theirs touches INSERT ALL, RETURNING or the bulk text (checked from git
  by 89). So the second round trip is 19c's baseline, open for the 10046 trace; the assertion stays
  as it is on 19c until the cause is named. The bulk INSERT build is mergeable with nothing left on
  89's side; it lands on the user's word, rebased onto main's head. A strict run of main at 0e5b5f9
  on 19c is the user's call, not a condition.
- 2026-11-01 (landed): 33 landed the 19c work on main on the user's word, local and not pushed, main at
  990b7e2. On top of bb152b7: the server-versions commits e60eca0, 6305a5f, d1e29bf (design.md's table
  keeps main's function-based index row and the MariaDB 1020 entry); the design as reviewed, c636ca6 =
  feaff40, 2713a81 = dd0cf56, dbf4b81 = cb5ce58; the build as reviewed, b3c29b4 = ae274d0, f1c4742 =
  e38d6c9, daecdac = 20dc64b (range-diff by 89: every pair identical); probe 9c' 990b7e2 (the Oracle
  forms test's bulk INSERT metadata insert as INSERT ... WHERE NOT EXISTS). Unit tests 416/416 and
  spotless pass (33). Open here: form4's second round trip on 19c (the 10046 trace), a strict 19c run
  of main (the user's call).

### 2026-11-01 - Form 4's second round trip on 19c is 19c's count of a failed call

**By:** Claude Code (the 19c session)
- The forms probe 8b (`roundTripsOfAFailedCall`, 0c73e34 on `oracle-19c`), through ojdbc alone and
  the counting proxy, on 19c (run 41308957896): a plain UPDATE 1 round trip by `v$mystat` and 1
  client chunk, an INSERT failing with ORA-00001 1 and 2, the failing INSERT then the UPDATE 2 and
  3. Form 4's INSERT over a tombstone, the INSERT ALL that fails then the block, counts the same 2
  (each pass [2, 2]). On 23 the test's own note records a failing INSERT ALL then an UPDATE through
  ojdbc as 1: 19c's `v$mystat` counts a failed call's round trip and 23's does not. The driver
  makes the same two calls on both; no extra call on 19c.
- `form4InsertAsOneStatement` expects 2 below 23 and 1 otherwise (0d16f47); design 4.4's open item
  replaced by the cause. The 19c workflow uploads the 23 job's reports too, for 8b's 23 figures,
  to be added to 8b's comment. Reviewed by the design session (89): no objection. The 10046 trace
  is not needed.

### 2026-11-02 - Probe 8b on both servers; 19c in CI and on main

**By:** Claude Code (the 19c session)
- Probe 8b on both jobs of run 41318145531 (`oracle-19c` at ebdb2ee, both green), `v$mystat` round
  trips / the proxy's client chunks: a plain UPDATE 19c 1/1, 23 1/1; an INSERT failing with
  ORA-00001 19c 1/2, 23 0/2; the failing INSERT then the UPDATE 19c 2/3, 23 1/3. The client's
  chunks are equal; only 19c's counter takes the failed call. In 8b's Javadoc (825c240); the
  design session (89) has no objection.
- `oracle-19c` merged into main (642cdb1); the 19c job moved into `ci.yml`, `oracle-19c.yml`
  removed, README lists 19c as supported from 3.20.1 (e4a4352).

### 2026-11-03 - PR #11 reviewed and merged; two follow-ups found after the merge

**By:** Claude Code (the design session, 89)
- The review record, written down after the fact (the verdicts went to the 19c session by message
  on 2026-11-02): 30dede3 (`CatalogSessionsExtension` and the package-private
  `NativeJdbcDriver.releaseCatalogSessions()`; `Engines.importTable`'s retry) mergeable; f75db6e
  with 1c294ad (`Engines.catalog()`/`track()`/`releaseCatalogSessions()`, the tests' own catalogs
  released after each class too; the 19c session's measure: the suite's low point 3-7 sessions,
  the peak 29) mergeable, run 41444738024 green on all eight jobs. 7d0acbc ("hold the tracked
  catalogs weakly", after Copilot's comment on the PR) went in after the review and was not
  reviewed before the merge. The user squash-merged PR #11 as 146d0bf (2026-11-02 14:43 UTC; the
  squash's tree equals 7d0acbc's; the branch is deleted on origin).
- CI on 146d0bf: the push's run 41464633802 failed in the 19c job alone,
  `WriteIntegrationTest.reviewFindings` at its first import (`rsd.t`): ScalarDB's
  `getImportTableMetadata`, ojdbc's `getColumns` PL/SQL, `ORA-00604: error occurred at recursive
  SQL level 1` with `ORA-08177` as the second line. 19c reports the 08177 inside ORA-00604, so the
  SQLException's error code is 604 and `causedBy08177` (error code 8177 alone) never matches: the
  retry cannot fire on the one server that raises the error. Fix: match the message as well
  (`ORA-08177` in `getMessage()`, or code 604 carrying it). The other seven jobs passed. The
  scheduled Isolation run 41496933170 the same evening failed SQL Server's
  `MaelstromIntegrationTest.history_failsAtSerializableWithoutScalarDbsSettings LIST_APPEND` (an
  anomaly expected within ANOMALY_SECONDS, none came); the 2026-10-30 scheduled run failed the
  PostgreSQL `history_failsWithoutTheDriver` the same way; not this merge's.
- 7d0acbc reviewed now: the weak set drops a catalog once the collector reaches it, and nothing
  then closes its session. Probe on the container (scratchpad `ora/gcsess2.jsh`, ojdbc 23.26, JDK
  25): five connections opened, their references dropped, four `System.gc()` over 15 s: all five
  SYSTEM sessions stayed in `v$session`; a held sixth left on `close()`. ojdbc 23's socket is an
  NIO `SocketChannel`, which has no cleaner (`javap`: `NioSocketImpl` registers one,
  `SocketChannelImpl` none). So a test-local catalog collected before its class's afterAll holds
  its session to the JVM's end, as before f75db6e; the 3-7 low point was measured with the strong
  list and not again after. Recommendation: the strong list back (f75db6e's form) with a Javadoc
  sentence saying why (Copilot's point is ~50 catalogs' metadata, nothing beside their sessions).
  Both follow-ups are one-liners for the 19c session on the user's word; 89 reviews.
- The 19c job's setup prints `ORA-02065: illegal option for ALTER SYSTEM` for
  `_disable_last_successful_login_time` (ci.yml since e4a4352): 19c does not take the parameter;
  the step goes on; cosmetic.
- Local main (88b3a49) is 8 commits past the fork point 10ebd49 and lacks the squash;
  `git merge-tree` finds no conflict (both sides touch `Engines.java` and
  `InlineIntegrationTest.java`, 77ece28's inline isolation against the squash's catalog tracking,
  in different hunks). A merge of origin/main keeps the eight quoted hashes, and main already
  carries merges (642cdb1). On the user's word, with a build after.
- 2026-11-04: on the user's word (typed in 89's session) both one-line fixes were sent to the 19c
  session as a build request, a PR from origin/main (146d0bf): the message-matching
  `causedBy08177` and the strong list back with a Javadoc sentence; the ORA-02065 guard optional.
  89 reviews from `git show`; the merge of origin/main into the local main still waits on the
  user's word.
- 2026-11-04, review record for eee99c1 (`ci-oracle-flakies-followups`, one commit on 146d0bf, the
  19c session; not pushed yet): mergeable once one comment is added. The three hunks are as asked:
  `causedBy08177` takes error code 8177 or `ORA-08177` in the message (19c's ORA-00604 wrapping,
  the report's message carries the second line, `String.valueOf` guards a null); `CATALOGS` is
  f75db6e's `ArrayList` again, the diff against f75db6e being the Javadoc and the matcher only;
  the 19c job's refused ALTER SYSTEM goes. The change wanted: a comment where the ALTER SYSTEM was,
  in the 19c job's start step, because the parameter was not cosmetic. The fixture sets
  `_disable_last_successful_login_time` only for the container it starts (`ORACLE.url`), and its
  Javadoc requires a DBA to have set it on an external database; 19c refuses it (ORA-02065), so the
  19c job runs without todo 105's mitigation: each logon writes SYS.USER$, and a hard parse inside a
  SERIALIZABLE transaction reads USER$ at the snapshot and fails with ORA-08177, which is the import
  failure's shape (ORA-00604 at recursive SQL level 1, ojdbc's getColumns PL/SQL). The retry covers
  the import on 19c; ScalarDB's record commit, which ignores the failure and leaves rows prepared
  (todo 105), stays exposed there, named by `ReadIntegrationTest`'s check after each commit. No
  19c-side switch is known to 89. The comment should say that much in two lines and point at todo
  105. The proof is the PR's CI, the 19c job above all.
- 2026-11-04, later: 803615f (eee99c1 + the three-line comment in the 19c job's start step: not
  mitigated, 19c refuses the parameter, each logon writes SYS.USER$, a SERIALIZABLE hard parse can
  fail with ORA-08177, the import retried, ScalarDB's commit exposed, todo 105 named) reviewed:
  mergeable as the branch stands, `ci-oracle-flakies-followups` at 803615f, two commits on 146d0bf,
  5 lines of ci.yml and 15 of Engines.java in all. The push and the PR are on the user's word in the
  19c session; the PR's CI, the 19c job above all, is the proof. The records keep naming the design
  session 89 (c4 is only its message address).
- 2026-11-04: origin/main (146d0bf) merged into the local main on the user's word, a9bbf08 (parents
  806e07f and 146d0bf; 44 files, no conflict; `Engines.java` and `InlineIntegrationTest.java` carry
  both sides, 77ece28's inline isolation and the squash's catalog tracking); the test fixtures and
  the integration tests compile (warnings only). The local main is 47 commits past origin/main,
  nothing pushed.
- 2026-11-04: PR #12 opened by the 19c session on the user's word, head 803615f, the reviewed head.
  Its Changelog "entry" check failed at once: no changie entry and no `no-changelog` label (PR #11
  carried the label); tests and CI are what the label is for, and adding it re-runs the check. Told
  the 19c session to put the label to the user. The seven integration jobs run; the 19c job is the
  proof.
- 2026-11-04, later: PR #12's eight build checks pass at 803615f (run 41785792579; Oracle 23 18m12s,
  19c 19m10s); only the Changelog entry check waits on the `no-changelog` label. The 19c session's
  point, agreed: a pass does not show the retry fired, since it logs nothing and the ORA-08177 is
  intermittent. Proposed to the user: one `System.err` line in `Engines.importTable`'s catch per
  retried attempt and one on the third failure, kept by gradle in the report's system-err, so each
  run's report says whether 19c's flaky struck and whether three tries sufficed; a commit on #12 or
  a later PR, the user's call.
- 2026-11-04: PR #12 merged by the user as 3314b0c (08:00 UTC; a squash whose tree equals the
  reviewed 803615f, nothing added after the review). origin/main merged into the local main as
  6a4845c on the pattern of the user's word for #11 (no conflict, the fixtures compile). CI on main
  at 3314b0c was in progress when read. The retry log line stays proposed, the user's call. With
  #11 and #12 in, the ORA-12516 climb and the 19c import retry are on main; the 19c job's
  SYS.USER$ exposure of ScalarDB's commit (todo 105) stays, named by `ReadIntegrationTest`.
