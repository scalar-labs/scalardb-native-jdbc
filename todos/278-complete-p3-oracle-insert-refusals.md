---
status: complete
priority: p3
issue_id: "278"
tags: [code-review, oracle, usability]
dependencies: []
---

# Oracle INSERT forms the driver refuses or fails where the vendor succeeds

## Problem Statement

1. Key 0 into a BY DEFAULT identity key: Oracle identity columns get the " auto_increment" tag, so the
   MySQL-only `AND d.k <> 0` guard (Writes 500-504, todo 218) applies: refused; Oracle stores 0.
2. A quoted lower-case identity key `"id"`: the stored name goes to `prepareStatement(sql, String[])`
   unquoted (Writes 1484) and ojdbc folds it: ORA-00904 "ID", though no keys were asked.
3. Multi-row VALUES leaving the identity key out, no keys asked: the driver always asks ojdbc for keys,
   ORA-63809; the vendor inserts both rows. Refuse clearly, or insert row by row.
4. `FOR UPDATE OF x` with x not otherwise read: StatementShape (674) accepts it, SplitRead.columnsRead
   (213) leaves x out of the committed copy: ORA-00904 "X".

## Proposed Solutions

1. Fix each (small each).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
- 2026-10-20: fixed. 1: the key-0 guard is MySQL/MariaDB only. 2: key names quoted for ojdbc (measured). 3: ORA-63809 refused with 0AN01 and a message. 4: Oracle OF columns join the columns the copy reads. Tested in MoreWrites on Oracle.
