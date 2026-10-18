---
status: complete
priority: p2
issue_id: "240"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# User LOCK IN SHARE MODE on a plain table fails on MariaDB

## Problem Statement

Router.java ~492: the routed text comes from ast.lockingEverySelect, built on SqlAst.source, which for
MySQL/MariaDB is the text lockInShareModeAsForShare rewrote; the user's LOCK IN SHARE MODE reaches the
server as FOR SHARE. Probe: `SELECT v FROM p WHERE id = 1 LOCK IN SHARE MODE` at SERIALIZABLE gives
42000/1064 on MariaDB. Before 77e2295 plain routes sent the original sql.

## Proposed Solutions

### Option 1
Splice the clause into the original sql at the same offsets (the rewrite pads, keeping them).

## Acceptance Criteria

- [ ] A user's LOCK IN SHARE MODE, outer or in a subquery, runs on MariaDB at SERIALIZABLE

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
- 2026-10-18: fixed: the clause is spliced into the application's own text, offsets the same; tested on MariaDB.
