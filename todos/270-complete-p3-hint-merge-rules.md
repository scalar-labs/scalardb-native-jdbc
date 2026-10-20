---
status: complete
priority: p3
issue_id: "270"
tags: [code-review, sqlserver, serializable, isolation]
dependencies: []
---

# Hint merging at SERIALIZABLE: one rule for HOLDLOCK and conflicting hints

## Problem Statement

- Router ~l.770 skips HOLDLOCK when HOLDLOCK/SERIALIZABLE is there; `holdingEveryTable`
  (SqlAst 1188-1191) always appends: `p WITH (HOLDLOCK)` → `WITH (HOLDLOCK, HOLDLOCK)`.
- Isolation hints the pass keeps conflict with HOLDLOCK and give vendor errors instead of 0AN01:
  READCOMMITTEDLOCK, REPEATABLEREAD (Msg 1047, also on a ScalarDB table, where REPEATABLEREAD is an
  accepted lock hint), READPAST (Msg 650); READUNCOMMITTED, NOLOCK's synonym, is not refused on plain
  tables as NOLOCK is.
- Legacy `FROM pp.q (NOLOCK)` becomes `(NOLOCK, HOLDLOCK)` → Msg 207/1018.
- `SELECT v INTO #tt FROM pp.q` puts HOLDLOCK on the INTO target → Msg 319.
All fail closed.

## Proposed Solutions

1. One helper: drop HOLDLOCK where HOLDLOCK/SERIALIZABLE present, replace REPEATABLEREAD by HOLDLOCK,
   refuse READUNCOMMITTED/NOLOCK/READCOMMITTED(LOCK)/READPAST/SNAPSHOT at SERIALIZABLE with 0AN01;
   skip SELECT INTO targets (small–medium).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
- 2026-10-20: fixed. holdingEveryTable: HOLDLOCK not added where HOLDLOCK or SERIALIZABLE stands; REPEATABLEREAD replaced by HOLDLOCK, padded; NOLOCK, READUNCOMMITTED, READCOMMITTED(LOCK), READPAST, SNAPSHOT refused (0AN01); a list without WITH gets one (Msg 1018 measured otherwise); SELECT INTO targets skipped. The ScalarDB-table locking read drops REPEATABLEREAD when it adds HOLDLOCK. Unit tests and SnapshotIntegrationTest on SQL Server.
