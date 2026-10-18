---
status: pending
priority: p2
issue_id: "232"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# Passthrough SET of the isolation level is inconsistent at SERIALIZABLE

## Problem Statement

Router.java:1708-1716. At driver SERIALIZABLE, a passthrough SET SESSION TRANSACTION ISOLATION LEVEL
REPEATABLE READ is ignored (vendor reports RR): the driver keeps reporting 8 and locking. A passthrough
... SERIALIZABLE leaves the vendor at native SERIALIZABLE under the driver's re-reads, unlike
setTransactionIsolation. Verified by probe on both engines.

## Proposed Solutions

### Option 1
Read the level from the SET text: SERIALIZABLE -> driver SERIALIZABLE with the vendor reset to RR; REPEATABLE READ -> SNAPSHOT.

### Option 2
Refuse SQL isolation SETs into or out of SERIALIZABLE on these engines.

## Acceptance Criteria

- [ ] Both directions leave the driver's level and the vendor's level consistent with setTransactionIsolation

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
