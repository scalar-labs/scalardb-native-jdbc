---
status: pending
priority: p3
issue_id: "245"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# NOWAIT / SKIP LOCKED outer reads now wait on their subqueries

## Problem Statement

A user's FOR UPDATE SKIP LOCKED / FOR SHARE NOWAIT at SERIALIZABLE gets plain LOCK IN SHARE MODE on
its subqueries, which can wait where the user asked not to.

## Proposed Solutions

### Option 1
Give subqueries the outer's NOWAIT/SKIP LOCKED option (SKIP LOCKED in a subquery changes results: likely NOWAIT only), or document.

## Acceptance Criteria

- [ ] Behaviour chosen and documented

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
