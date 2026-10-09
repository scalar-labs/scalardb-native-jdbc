---
status: complete
priority: p3
issue_id: "023"
tags: [code-review, security, build]
dependencies: []
---

# Supply chain: mutable action ref with inherited secrets in auto-pr.yml, logback test dependency with a known CVE

## Problem Statement

One workflow consumes a shared action by a mutable branch reference while inheriting the repository's secrets and declaring no permissions, so a change to that branch runs with the pull-request token here. Separately, the logback version pinned for the test runtime has a published advisory. Neither affects the shipped driver jar (logback is test-only and the vector is the `SocketServer` component the tests never start), which keeps this at P3, but both are one-line fixes that close a door.

## Findings

All line numbers refer to commit 06c137f.

- `.github/workflows/auto-pr.yml:18` uses `scalar-labs/actions/...@main` with `secrets: inherit` (which includes `GH_PR_PAT`): a mutable ref and no `permissions:` block. `ci.yml` by contrast pins its actions by SHA and sets `permissions: contents: read`.
- `gradle/libs.versions.toml:9` pins logback `1.5.32`, affected by GHSA-jhq6-gfmj-v8fx / CVE-2026-10532, fixed in `1.5.34`. It is `testRuntimeOnly`; the vulnerable component is `SocketServer`, which nothing here starts.
- OSV reports nothing for the other pinned versions as of the review date: jsqlparser 5.4, postgresql 42.7.14, mysql-connector-j 26.7.0, mariadb-java-client 3.5.10, mssql-jdbc 13.6.0.jre11, ojdbc11 23.26.3.0.0, slf4j 1.7.36, testcontainers 2.0.5, scalardb 3.19.1, junit 5.14.4, mockito 5.23.0, error_prone 2.50.0.
- The Gradle wrapper is pinned by SHA-256 with `validateDistributionUrl=true`; Dependabot is configured with groups. Both found acceptable by the security reviewer.

## Proposed Solutions

### Option 1: Pin the action to a SHA, add permissions, bump logback (recommended)

**Approach:** Replace `@main` with the commit SHA of the shared action (with the version as a trailing comment, as `ci.yml` does), add a minimal `permissions:` block to `auto-pr.yml`, and bump logback to `1.5.34` or let the next Dependabot run do it.

**Pros:**
- Matches the pattern already used in `ci.yml`
- Dependabot keeps the SHA current

**Cons:**
- The shared action's own release cadence decides how often the SHA moves

**Effort:** 15 minutes

**Risk:** Low

---

### Option 2: Remove auto-pr.yml until version branches exist

**Approach:** The workflow backports to version branches that do not exist yet and needs an org secret; delete it and add it back with the first `3.19` branch (the simplicity reviewer's suggestion, see todo 030).

**Pros:**
- Nothing to pin; no secret needed yet

**Cons:**
- Must remember to restore it, pinned, when the first version branch is cut

**Effort:** 5 minutes

**Risk:** Low

---

### Option 3: Keep `@main`, add permissions only

**Approach:** Add the `permissions:` block and leave the mutable ref, trusting the organisation-owned action.

**Pros:**
- Automatically follows fixes to the shared action

**Cons:**
- A compromised or mistaken push to `main` of the action still runs with the PAT here

**Effort:** 5 minutes

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `.github/workflows/auto-pr.yml:18`
- `gradle/libs.versions.toml:9`
- `.github/dependabot.yml` (grouping already covers both)

**Related components:**
- Todo 030 (build conventions): the question of whether `auto-pr.yml` should exist before version branches do
- Todo 021 (test classpath): logback moves to `integrationTest` only if that todo is done

**Database changes:** none

## Resources

- GHSA-jhq6-gfmj-v8fx / CVE-2026-10532 (logback, fixed in 1.5.34)
- GitHub docs: pinning actions to a full-length commit SHA; workflow `permissions`
- Review agents: security-sentinel (P3-6)

## Acceptance Criteria

- [ ] `auto-pr.yml` references the shared action by a full commit SHA and declares `permissions:`
- [ ] logback is at `1.5.34` or later in `gradle/libs.versions.toml`
- [ ] Dependabot continues to track both (no `ignore` added)
- [ ] CI passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated the security-sentinel (P3-6) finding; the OSV check of all pinned versions was done by that reviewer

**Learnings:**
- `ci.yml` already follows the SHA-pinning pattern; only the auto-PR workflow, copied from the sibling repository, does not

---

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- auto-pr.yml calls the shared workflow at a commit (0594c557, main of 2026-10-05) instead of @main, with a `permissions: contents: read` block; the shared workflow only reads the repository with the job token and opens PRs with GH_PR_PAT, which it does not declare, so `secrets: inherit` stays. Dependabot's github-actions ecosystem updates the pin.
- logback-classic 1.5.32 -> 1.5.38 (test runtime only), past the advisory's fixed version 1.5.34.

## Notes

- If Option 2 is chosen, record in `RELEASING.md` that the workflow must be restored and pinned when the first version branch is created.
